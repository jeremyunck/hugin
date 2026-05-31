#!/usr/bin/env bash
# install.sh — single-script installer for Hugin (MCP Agent)
#
# Usage:
#   ./install.sh             interactive install / reconfigure
#   ./install.sh --uninstall remove service, launcher, and optionally ~/.hugin
#
# All files go under HUGIN_HOME (default ~/.hugin).
# Environment variables collected during install are stored in ~/.hugin/hugin.env (chmod 600).
# The agent workspace (file/shell operations) lives at ~/.hugin/workspace.
set -euo pipefail

# ── constants ─────────────────────────────────────────────────────────────────
HUGIN_HOME="${HUGIN_HOME:-$HOME/.hugin}"
SERVICE_NAME="hugin"
LAUNCHER_NAME="hugin"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_USER="$(id -un)"
ENV_FILE="$HUGIN_HOME/hugin.env"
CONFIG_YML="$HUGIN_HOME/config/application.yml"
MCP_JSON="$HUGIN_HOME/config/mcp-servers.json"
LAUNCHER_PATH="/usr/local/bin/${LAUNCHER_NAME}"

# ── colours ───────────────────────────────────────────────────────────────────
info()    { printf '\033[1;34m[hugin]\033[0m %s\n' "$*"; }
success() { printf '\033[1;32m[hugin]\033[0m %s\n' "$*"; }
warn()    { printf '\033[1;33m[hugin]\033[0m %s\n' "$*"; }
die()     { printf '\033[1;31m[hugin]\033[0m %s\n' "$*" >&2; exit 1; }
ask()     { printf '\033[1;35m   >\033[0m %s' "$*"; }

require_cmd() { command -v "$1" >/dev/null 2>&1; }

# Run a command that requires sudo, retrying up to 3 times on auth failure.
# sudo_retry [--reason <description>] <cmd> [args...]
# Prompts for the system (macOS/Linux login) password with up to 3 retries.
sudo_retry() {
  local reason=""
  if [[ "${1:-}" == "--reason" ]]; then
    reason="$2"; shift 2
  fi
  local attempt=1 max=3
  if [[ -n "$reason" ]]; then
    info "sudo required: $reason"
  fi
  sudo -k  # clear any cached credentials so the password is always prompted fresh
  while true; do
    if sudo "$@"; then
      return 0
    fi
    if [[ $attempt -ge $max ]]; then
      die "Authentication failed after $max attempts. Aborting."
    fi
    warn "Incorrect password — attempt $attempt of $max. Try again."
    attempt=$((attempt + 1))
    sudo -k
  done
}

# ── OS detection + service/package helpers ────────────────────────────────────
OS_TYPE="linux"
if [[ "$(uname -s)" == "Darwin" ]]; then
  OS_TYPE="macos"
fi

if [[ "$OS_TYPE" == "macos" ]]; then
  PLIST_LABEL="com.hugin.agent"
  PLIST_PATH="$HOME/Library/LaunchAgents/${PLIST_LABEL}.plist"

  pkg_update()          { brew update -q; }
  pkg_install()         { brew install "$@"; }
  pkg_install_java()    {
    brew install --cask temurin@21 2>/dev/null \
      || brew install temurin@21 2>/dev/null \
      || die "Could not install Temurin 21 via Homebrew. Install manually from https://adoptium.net"
  }
  pkg_install_redis()   { brew install redis; }

  svc_install() {
    # Write a LaunchAgent plist (user-level, no sudo).
    mkdir -p "$HOME/Library/LaunchAgents"
    cat > "$PLIST_PATH" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>             <string>${PLIST_LABEL}</string>
  <key>UserName</key>          <string>${INSTALL_USER}</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>HUGIN_HOME</key>  <string>${HUGIN_HOME}</string>
    <key>AGENT_HOME</key>  <string>${HUGIN_HOME}</string>
  </dict>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>-c</string>
    <string>set -a; source ${ENV_FILE}; set +a; export PATH=${HUGIN_HOME}/venv/bin:/usr/local/bin:/usr/bin:/bin; exec /usr/bin/java -jar ${HUGIN_HOME}/bin/mcp-integration.jar --spring.config.additional-location=file:${CONFIG_YML}</string>
  </array>
  <key>WorkingDirectory</key>  <string>${HUGIN_HOME}</string>
  <key>StandardOutPath</key>   <string>${HUGIN_HOME}/logs/hugin.log</string>
  <key>StandardErrorPath</key> <string>${HUGIN_HOME}/logs/hugin.log</string>
  <key>RunAtLoad</key>         <true/>
  <key>KeepAlive</key>
  <dict><key>SuccessfulExit</key><false/></dict>
</dict>
</plist>
PLIST
    launchctl unload "$PLIST_PATH" 2>/dev/null || true
    launchctl load -w "$PLIST_PATH"
  }

  svc_uninstall() {
    launchctl unload "$PLIST_PATH" 2>/dev/null || true
    rm -f "$PLIST_PATH"
  }

  svc_start()     { launchctl start "$PLIST_LABEL"; }
  svc_stop()      { launchctl stop  "$PLIST_LABEL" 2>/dev/null || true; }
  svc_restart()   { svc_stop; sleep 1; svc_start; }
  svc_status()    { launchctl list "$PLIST_LABEL" 2>/dev/null || echo "not loaded"; }
  svc_logs()      { exec tail -f "$HUGIN_HOME/logs/hugin.log"; }
  svc_is_active() { launchctl list 2>/dev/null | grep -q "$PLIST_LABEL"; }
  svc_is_enabled(){ [[ -f "$PLIST_PATH" ]]; }
  svc_enable()    { launchctl load -w "$PLIST_PATH" 2>/dev/null || true; }
  svc_daemon_reload() { :; }  # no-op on macOS

  DISCORD_PLIST_LABEL="com.hugin.discord"
  DISCORD_PLIST_PATH="$HOME/Library/LaunchAgents/${DISCORD_PLIST_LABEL}.plist"

  discord_has_token() { grep -qE '^DISCORD_BOT_TOKEN=.+' "${ENV_FILE:-$HUGIN_HOME/hugin.env}" 2>/dev/null; }

  discord_svc_install() {
    if ! discord_has_token; then
      info "DISCORD_BOT_TOKEN not set in $ENV_FILE — skipping Discord bot service."
      return
    fi
    if [[ ! -f "$HUGIN_HOME/bin/agent-discord.jar" ]]; then
      info "agent-discord.jar not found — skipping Discord bot service."
      return
    fi
    mkdir -p "$HOME/Library/LaunchAgents"
    cat > "$DISCORD_PLIST_PATH" <<DISCORD_PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>             <string>${DISCORD_PLIST_LABEL}</string>
  <key>UserName</key>          <string>${INSTALL_USER}</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>HUGIN_HOME</key>  <string>${HUGIN_HOME}</string>
  </dict>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>-c</string>
    <string>set -a; source ${ENV_FILE}; set +a; exec /usr/bin/java -jar ${HUGIN_HOME}/bin/agent-discord.jar</string>
  </array>
  <key>WorkingDirectory</key>  <string>${HUGIN_HOME}</string>
  <key>StandardOutPath</key>   <string>${HUGIN_HOME}/logs/discord.log</string>
  <key>StandardErrorPath</key> <string>${HUGIN_HOME}/logs/discord.log</string>
  <key>RunAtLoad</key>         <true/>
  <key>KeepAlive</key>
  <dict><key>SuccessfulExit</key><false/></dict>
</dict>
</plist>
DISCORD_PLIST
    launchctl unload "$DISCORD_PLIST_PATH" 2>/dev/null || true
    launchctl load -w "$DISCORD_PLIST_PATH"
    success "Discord bot service installed."
  }

  discord_svc_uninstall() {
    launchctl unload "$DISCORD_PLIST_PATH" 2>/dev/null || true
    rm -f "$DISCORD_PLIST_PATH"
  }

  discord_svc_start()     { discord_has_token && launchctl start "$DISCORD_PLIST_LABEL" 2>/dev/null || true; }
  discord_svc_stop()      { launchctl stop "$DISCORD_PLIST_LABEL" 2>/dev/null || true; }
  discord_svc_restart()   { discord_svc_stop; sleep 1; discord_svc_start; }
  discord_svc_is_active() { launchctl list 2>/dev/null | grep -q "$DISCORD_PLIST_LABEL"; }

  svc_start_redis()    { brew services start redis; }
  svc_is_active_redis(){ brew services list 2>/dev/null | grep -E '^redis\s' | grep -q started; }

  SED_INPLACE() { sed -i '' "$@"; }

else
  # ── Linux / systemd ──────────────────────────────────────────────────────────
  SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"

  pkg_update()          { sudo apt-get update -qq; }
  pkg_install()         { sudo apt-get install -y "$@"; }
  pkg_install_java()    {
    sudo install -d -m 0755 /etc/apt/keyrings
    wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
      | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
    # shellcheck source=/dev/null
    . /etc/os-release
    echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] \
https://packages.adoptium.net/artifactory/deb ${VERSION_CODENAME} main" \
      | sudo tee /etc/apt/sources.list.d/adoptium.list >/dev/null
    sudo apt-get update -qq
    sudo apt-get install -y temurin-21-jdk
  }
  DISCORD_SERVICE_FILE="/etc/systemd/system/hugin-discord.service"

  discord_has_token() { grep -qE '^DISCORD_BOT_TOKEN=.+' "${ENV_FILE:-$HUGIN_HOME/hugin.env}" 2>/dev/null; }

  discord_svc_install() {
    if ! discord_has_token; then
      info "DISCORD_BOT_TOKEN not set in $ENV_FILE — skipping Discord bot service."
      return
    fi
    if [[ ! -f "$HUGIN_HOME/bin/agent-discord.jar" ]]; then
      info "agent-discord.jar not found — skipping Discord bot service."
      return
    fi
    sudo_retry --reason "write Discord systemd service file" tee "$DISCORD_SERVICE_FILE" > /dev/null <<DISCORD_SERVICE
# Hugin Discord bot service — managed by install.sh
[Unit]
Description=Hugin Discord Bot
After=network-online.target hugin.service
Wants=network-online.target

[Service]
Type=simple
User=${INSTALL_USER}
Environment=HUGIN_HOME=${HUGIN_HOME}
EnvironmentFile=${ENV_FILE}
WorkingDirectory=${HUGIN_HOME}
ExecStart=/usr/bin/java -jar ${HUGIN_HOME}/bin/agent-discord.jar
StandardOutput=append:${HUGIN_HOME}/logs/discord.log
StandardError=append:${HUGIN_HOME}/logs/discord.log
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
DISCORD_SERVICE
    sudo_retry --reason "reload systemd for Discord service" systemctl daemon-reload
    sudo systemctl enable hugin-discord
    success "Discord bot service installed."
  }

  discord_svc_uninstall() {
    sudo systemctl disable --now hugin-discord 2>/dev/null || true
    sudo rm -f "$DISCORD_SERVICE_FILE"
    sudo systemctl daemon-reload
  }

  discord_svc_start()     { discord_has_token && sudo systemctl start hugin-discord 2>/dev/null || true; }
  discord_svc_stop()      { sudo systemctl stop hugin-discord 2>/dev/null || true; }
  discord_svc_restart()   { sudo systemctl restart hugin-discord 2>/dev/null || true; }
  discord_svc_is_active() { systemctl is-active --quiet hugin-discord 2>/dev/null; }

  pkg_install_redis()   { sudo apt-get install -y redis-server; }

  svc_install() {
    sudo_retry --reason "write systemd service file to /etc/systemd/system/" tee "$SERVICE_FILE" > /dev/null <<SERVICE
# Hugin agent service — managed by install.sh
[Unit]
Description=Hugin MCP Agent Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${INSTALL_USER}
Environment=HUGIN_HOME=${HUGIN_HOME}
Environment=AGENT_HOME=${HUGIN_HOME}
EnvironmentFile=${ENV_FILE}
Environment=PATH=${HUGIN_HOME}/venv/bin:/usr/local/bin:/usr/bin:/bin
WorkingDirectory=${HUGIN_HOME}
ExecStart=/usr/bin/java -jar ${HUGIN_HOME}/bin/mcp-integration.jar \
  --spring.config.additional-location=file:${HUGIN_HOME}/config/application.yml
StandardOutput=append:${HUGIN_HOME}/logs/hugin.log
StandardError=append:${HUGIN_HOME}/logs/hugin.log
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
SERVICE
    sudo_retry --reason "reload systemd unit definitions" systemctl daemon-reload
  }

  svc_uninstall() {
    sudo systemctl disable --now "$SERVICE_NAME" 2>/dev/null || true
    sudo rm -f "$SERVICE_FILE"
    sudo systemctl daemon-reload
  }

  svc_start()     { sudo systemctl start   "$SERVICE_NAME"; }
  svc_stop()      { sudo systemctl stop    "$SERVICE_NAME"; }
  svc_restart()   { sudo systemctl restart "$SERVICE_NAME"; }
  svc_status()    { systemctl status       "$SERVICE_NAME"; }
  svc_logs()      { exec journalctl -u "$SERVICE_NAME" -f; }
  svc_is_active() { systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; }
  svc_is_enabled(){ systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; }
  svc_enable()    { sudo systemctl enable "$SERVICE_NAME"; }
  svc_daemon_reload() { sudo systemctl daemon-reload; }

  svc_start_redis()    { sudo systemctl enable --now redis-server; }
  svc_is_active_redis(){ systemctl is-active --quiet redis-server 2>/dev/null; }

  SED_INPLACE() { sed -i "$@"; }
fi

info "Detected OS: $OS_TYPE"

# ── wait for health ───────────────────────────────────────────────────────────
wait_for_health() {
  local max="${1:-45}" elapsed=0
  info "Waiting for agent server to become healthy (up to ${max}s)..."
  until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
    elapsed=$((elapsed + 1))
    if [[ $elapsed -ge $max ]]; then
      warn "Server did not become healthy within ${max}s."
      warn "Inspect logs:  hugin logs"
      return 1
    fi
    if (( elapsed % 5 == 0 )); then
      info "Still waiting... (${elapsed}s elapsed)"
    fi
    sleep 1
  done
  success "Server healthy at http://localhost:8080"
}

# ── reinstall (non-interactive) ───────────────────────────────────────────────
_force_reinstall=false
if [[ "${1:-}" == "--reinstall" ]]; then
  _force_reinstall=true
fi

# ── uninstall ─────────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--uninstall" ]]; then
  info "Stopping and removing $SERVICE_NAME service..."
  svc_uninstall
  sudo_retry --reason "remove launcher from $LAUNCHER_PATH" rm -f "$LAUNCHER_PATH"
  success "Service and launcher removed."

  if [[ -d "$HUGIN_HOME" ]]; then
    ask "Delete $HUGIN_HOME (config + workspace + logs)? [y/N] "; read -r _confirm; echo
    if [[ "$(echo "$_confirm" | tr '[:upper:]' '[:lower:]')" == "y" ]]; then
      rm -rf "$HUGIN_HOME"
      success "$HUGIN_HOME deleted."
    else
      info "Kept $HUGIN_HOME."
    fi
  fi
  exit 0
fi

# ── detect existing install ───────────────────────────────────────────────────
ALREADY_INSTALLED=false
SKIP_BUILD=false
SKIP_CREDENTIALS=false

if [[ -f "$HUGIN_HOME/bin/mcp-integration.jar" ]]; then
  ALREADY_INSTALLED=true
  if [[ "$_force_reinstall" == "true" ]]; then
    SKIP_CREDENTIALS=true
    info "Reinstall mode — jars will be rebuilt using existing credentials."
  else
    warn "Existing Hugin installation detected at $HUGIN_HOME."
    echo
    echo "  1) Reconfigure only            (keep existing jars, update env + config, restart service)"
    echo "  2) Full reinstall              (rebuild jars from source, update everything)"
    echo "  3) Full reinstall, no prompts  (rebuild jars, reuse existing credentials)"
    echo
    ask "Choice [1]: "; read -r _choice; echo
    _choice="${_choice:-1}"
    if [[ "$_choice" == "1" ]]; then
      SKIP_BUILD=true
      info "Will reconfigure without rebuilding."
    elif [[ "$_choice" == "3" ]]; then
      SKIP_CREDENTIALS=true
      info "Full reinstall selected — jars will be rebuilt using existing credentials."
    else
      info "Full reinstall selected — jars will be rebuilt."
    fi
  fi
fi

echo
info "Hugin home : $HUGIN_HOME"
info "Repo root  : $REPO_DIR"
echo

# ── 1. directory tree ─────────────────────────────────────────────────────────
mkdir -p \
  "$HUGIN_HOME/bin" \
  "$HUGIN_HOME/config" \
  "$HUGIN_HOME/venv" \
  "$HUGIN_HOME/workspace" \
  "$HUGIN_HOME/logs"
info "Directory tree ready at $HUGIN_HOME"

# ── 2. system dependencies ────────────────────────────────────────────────────
if [[ "$OS_TYPE" == "macos" ]]; then
  if ! require_cmd brew; then
    die "Homebrew is required on macOS. Install it from https://brew.sh and re-run."
  fi

  java_ok=false
  if require_cmd java && java -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'; then
    info "Java 21+ already present."
    java_ok=true
  fi
  if [[ "$java_ok" == "false" ]]; then
    warn "Java 21 not found — installing Temurin 21 via Homebrew..."
    pkg_install_java
  fi

  require_cmd git     || pkg_install git
  require_cmd mvn     || pkg_install maven
  require_cmd python3 || pkg_install python3
  require_cmd curl    || pkg_install curl

else
  # Linux: apt-based installs
  need_apt_update=false
  pkgs_to_install=()

  java_ok=false
  if require_cmd java && java -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'; then
    info "Java 21+ already present."
    java_ok=true
  fi

  if [[ "$java_ok" == "false" ]]; then
    warn "Java 21 not found — will install Temurin JDK 21 via Adoptium apt repo."
    pkg_install_java
  fi

  require_cmd git     || pkgs_to_install+=(git)
  require_cmd mvn     || pkgs_to_install+=(maven)
  require_cmd python3 || pkgs_to_install+=(python3)
  require_cmd curl    || pkgs_to_install+=(curl)

  if ! python3 -c "import venv" 2>/dev/null; then
    pkgs_to_install+=(python3-venv)
  fi

  if [[ ${#pkgs_to_install[@]} -gt 0 ]]; then
    info "Installing system packages: ${pkgs_to_install[*]}"
    pkg_update
    pkg_install "${pkgs_to_install[@]}"
  fi
fi

# ── 3. prompts ────────────────────────────────────────────────────────────────

# Incrementally persist collected values so a mid-run failure doesn't lose them.
save_env() {
  mkdir -p "$HUGIN_HOME/db"
  cat > "$ENV_FILE" <<EOF
# Hugin environment — sourced by the service and the hugin launcher.
# Permissions: 600 (owner-read-only).  Do not commit this file.

OPEN_ROUTER_API_KEY=${OPENROUTER_KEY:-}
LLM_MODEL=${LLM_MODEL:-}

# Secure the /api/agent/** endpoints with an X-API-Key header (leave blank to disable).
AGENT_API_KEY=${AGENT_API_KEY:-}

# Discord bot (optional — leave blank to disable)
DISCORD_BOT_TOKEN=${DISCORD_BOT_TOKEN:-}

# Long-term Redis-backed semantic memory
MEMORY_ENABLED=${MEMORY_ENABLED:-false}
REDIS_HOST=${REDIS_HOST_VAL:-}
REDIS_PORT=${REDIS_PORT_VAL:-6379}

# Cloud-agent feature (POST /api/agents — clone repo, run agent loop)
CLOUD_AGENTS_ENABLED=${CLOUD_AGENTS_ENABLED:-false}
GITHUB_TOKEN=${GITHUB_TOKEN:-}

# New Relic monitoring (optional — leave blank to disable)
NEW_RELIC_TOKEN=${NEW_RELIC_TOKEN:-}
NEW_RELIC_ENABLED=${NEW_RELIC_ENABLED:-false}

# Hugin home directory (workspace root is $HUGIN_HOME/workspace)
AGENT_HOME=${HUGIN_HOME}

# H2 database for user accounts (dashboard login)
DB_URL=jdbc:h2:file:${HUGIN_HOME}/db/hugin

# Dashboard admin account — used only on first startup to create the admin user.
# After the user is created the password is stored hashed in the database.
ADMIN_USERNAME=${ADMIN_USERNAME:-}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-}

# JWT secret for signing dashboard session tokens (min 32 chars).
# Changing this invalidates all existing sessions.
JWT_SECRET=${JWT_SECRET:-}
EOF
  chmod 600 "$ENV_FILE"
}

# Load existing values so they can be used as defaults on reconfigure
_existing_key="" _existing_agent_key="" _existing_redis_host="" _existing_redis_port="6379"
_existing_model="" _existing_github_token="" _existing_discord_token="" _existing_nr_token=""
if [[ -f "$ENV_FILE" ]]; then
  _existing_key=$(grep -E '^OPEN_ROUTER_API_KEY=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_agent_key=$(grep -E '^AGENT_API_KEY=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_redis_host=$(grep -E '^REDIS_HOST=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_redis_port=$(grep -E '^REDIS_PORT=' "$ENV_FILE" | cut -d= -f2- || echo "6379")
  _existing_model=$(grep -E '^LLM_MODEL=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_github_token=$(grep -E '^GITHUB_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_discord_token=$(grep -E '^DISCORD_BOT_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_nr_token=$(grep -E '^NEW_RELIC_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)
fi

# Initialise all config vars to empty so save_env can be called at any point.
OPENROUTER_KEY="" LLM_MODEL="" AGENT_API_KEY="" MEMORY_ENABLED=false
REDIS_HOST_VAL="" REDIS_PORT_VAL=6379 GITHUB_TOKEN="" CLOUD_AGENTS_ENABLED=false
ADMIN_USERNAME="" ADMIN_PASSWORD="" JWT_SECRET="" DISCORD_BOT_TOKEN=""
NEW_RELIC_TOKEN="" NEW_RELIC_ENABLED=false

if [[ "$SKIP_CREDENTIALS" == "true" ]]; then
  info "Reusing existing credentials from $ENV_FILE — skipping interactive prompts."
  OPENROUTER_KEY="$_existing_key"
  LLM_MODEL="${_existing_model:-openai/gpt-oss-120b}"
  AGENT_API_KEY="$_existing_agent_key"
  MEMORY_ENABLED=$(grep -E '^MEMORY_ENABLED=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || echo "false")
  REDIS_HOST_VAL="$_existing_redis_host"
  REDIS_PORT_VAL="${_existing_redis_port:-6379}"
  GITHUB_TOKEN="$_existing_github_token"
  CLOUD_AGENTS_ENABLED=$(grep -E '^CLOUD_AGENTS_ENABLED=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || echo "false")
  ADMIN_USERNAME=$(grep -E '^ADMIN_USERNAME=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || echo "admin")
  ADMIN_PASSWORD=$(grep -E '^ADMIN_PASSWORD=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || echo "")
  JWT_SECRET=$(grep -E '^JWT_SECRET=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || echo "")
  if [[ -z "$JWT_SECRET" ]]; then
    JWT_SECRET=$(python3 -c "import secrets; print(secrets.token_urlsafe(48))" 2>/dev/null \
                 || openssl rand -base64 48 2>/dev/null | tr -d '\n')
    info "JWT secret auto-generated."
  fi
  # Prefer existing env file value, fall back to shell env var (handles first-run after manual token setup)
  DISCORD_BOT_TOKEN="${_existing_discord_token:-${DISCORD_BOT_TOKEN:-}}"
  NEW_RELIC_TOKEN="${_existing_nr_token:-}"
  NEW_RELIC_ENABLED=$(grep -E '^NEW_RELIC_ENABLED=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || echo "false")
  success "Credentials loaded."
else

echo
printf '\033[1;34m─── Environment Configuration ─────────────────────────────────────────\033[0m\n'
echo

# 3a. OpenRouter API key (required)
OPENROUTER_KEY=""
while [[ -z "$OPENROUTER_KEY" ]]; do
  if [[ -n "$_existing_key" ]]; then
    ask "OpenRouter API key [current hidden, press Enter to keep]: "
    read -rsp "" OPENROUTER_KEY; echo
    [[ -z "$OPENROUTER_KEY" ]] && OPENROUTER_KEY="$_existing_key"
  else
    ask "OpenRouter API key (sk-or-v1-...): "
    read -rsp "" OPENROUTER_KEY; echo
  fi
  if [[ -z "$OPENROUTER_KEY" ]]; then
    warn "An OpenRouter API key is required (used for the LLM and web search). Try again."
  fi
done
success "OpenRouter API key set."
save_env

# 3b. LLM model (optional)
echo
info "Default model: openai/gpt-oss-120b (OpenRouter)"
ask "LLM model override [press Enter for default${_existing_model:+, current: $_existing_model}]: "
read -r LLM_MODEL_INPUT; echo
LLM_MODEL="${LLM_MODEL_INPUT:-${_existing_model:-openai/gpt-oss-120b}}"
info "Model: $LLM_MODEL"
save_env

# 3c. Agent API key (optional — secures /api/agent/** with X-API-Key header)
echo
info "Agent API key: if set, all /api/agent/** calls require  X-API-Key: <value>"
if [[ -n "$_existing_agent_key" ]]; then
  ask "Agent API key [current hidden, press Enter to keep, or type 'clear' to remove]: "
  read -rsp "" _agent_key_input; echo
  if [[ "$(echo "$_agent_key_input" | tr '[:upper:]' '[:lower:]')" == "clear" ]]; then
    AGENT_API_KEY=""
    info "Agent API key cleared — endpoints will be open."
  elif [[ -z "$_agent_key_input" ]]; then
    AGENT_API_KEY="$_existing_agent_key"
    info "Agent API key kept."
  else
    AGENT_API_KEY="$_agent_key_input"
    success "Agent API key updated."
  fi
else
  ask "Agent API key (leave blank to leave endpoints open): "
  read -rsp "" AGENT_API_KEY; echo
  if [[ -n "$AGENT_API_KEY" ]]; then
    success "Agent API key set."
  else
    info "Endpoints will be open (no X-API-Key required)."
  fi
fi
save_env

# 3d. Redis for long-term memory (optional)
echo
info "Long-term memory requires a Redis instance."
MEMORY_ENABLED=false
REDIS_HOST_VAL=""
REDIS_PORT_VAL=6379

echo "  1) None   — disable long-term memory"
echo "  2) Local  — install/start Redis on this machine"
echo "  3) Host   — connect to an existing Redis host"
echo

_redis_default_choice=1
if [[ -n "$_existing_redis_host" ]]; then
  if [[ "$_existing_redis_host" == "localhost" || "$_existing_redis_host" == "127.0.0.1" ]]; then
    _redis_default_choice=2
  else
    _redis_default_choice=3
  fi
fi

ask "Redis option [${_redis_default_choice}]: "; read -r _redis_choice; echo
_redis_choice="${_redis_choice:-${_redis_default_choice}}"

case "$_redis_choice" in
  2)
    # Local Redis
    REDIS_HOST_VAL="localhost"
    ask "Redis port [6379]: "; read -r _port_input; echo
    REDIS_PORT_VAL="${_port_input:-6379}"

    if ! require_cmd redis-server; then
      info "Redis not found locally — installing..."
      pkg_install_redis
    fi

    if ! svc_is_active_redis 2>/dev/null; then
      info "Starting Redis..."
      svc_start_redis
    else
      info "Local Redis already running."
    fi

    info "Testing Redis connectivity at ${REDIS_HOST_VAL}:${REDIS_PORT_VAL}..."
    if require_cmd redis-cli && redis-cli -h "$REDIS_HOST_VAL" -p "$REDIS_PORT_VAL" ping >/dev/null 2>&1; then
      MEMORY_ENABLED=true
      success "Local Redis reachable — long-term memory enabled."
    else
      warn "Cannot reach local Redis at ${REDIS_HOST_VAL}:${REDIS_PORT_VAL}. Long-term memory will be DISABLED."
      REDIS_HOST_VAL=""
      MEMORY_ENABLED=false
    fi
    ;;
  3)
    # Remote Redis host
    _redis_host_default="${_existing_redis_host:-}"
    ask "Redis host${_redis_host_default:+ [current: ${_redis_host_default}]}: "; read -r REDIS_HOST_INPUT; echo
    REDIS_HOST_VAL="${REDIS_HOST_INPUT:-${_redis_host_default}}"

    ask "Redis port [${_existing_redis_port}]: "; read -r _port_input; echo
    REDIS_PORT_VAL="${_port_input:-${_existing_redis_port}}"

    if [[ -z "$REDIS_HOST_VAL" ]]; then
      warn "No host entered — long-term memory disabled."
    else
      info "Testing Redis connectivity at ${REDIS_HOST_VAL}:${REDIS_PORT_VAL}..."
      if require_cmd redis-cli && redis-cli -h "$REDIS_HOST_VAL" -p "$REDIS_PORT_VAL" ping >/dev/null 2>&1; then
        MEMORY_ENABLED=true
        success "Redis reachable — long-term memory enabled."
      else
        warn "Cannot reach Redis at ${REDIS_HOST_VAL}:${REDIS_PORT_VAL}. Long-term memory will be DISABLED."
        warn "Fix connectivity and re-run  $LAUNCHER_NAME config  to enable it later."
        REDIS_HOST_VAL=""
        MEMORY_ENABLED=false
      fi
    fi
    ;;
  *)
    # None (option 1 or anything else)
    info "Long-term memory disabled."
    ;;
esac
save_env

# 3e. GitHub token (optional — for cloud-agent repo cloning)
echo
info "GitHub token enables the cloud-agent feature (/api/agents — repo clone + agent loop)."
if [[ -n "$_existing_github_token" ]]; then
  ask "GitHub personal-access token [current hidden, press Enter to keep, or 'clear' to remove]: "
  read -rsp "" _gh_token_input; echo
  if [[ "$(echo "$_gh_token_input" | tr '[:upper:]' '[:lower:]')" == "clear" ]]; then
    GITHUB_TOKEN=""
    info "GitHub token cleared."
  elif [[ -z "$_gh_token_input" ]]; then
    GITHUB_TOKEN="$_existing_github_token"
    info "GitHub token kept."
  else
    GITHUB_TOKEN="$_gh_token_input"
    success "GitHub token updated."
  fi
else
  ask "GitHub personal-access token (leave blank to skip): "
  read -rsp "" GITHUB_TOKEN; echo
  if [[ -n "$GITHUB_TOKEN" ]]; then
    success "GitHub token set."
  else
    info "Cloud-agent repo cloning will use public repos only."
  fi
fi

CLOUD_AGENTS_ENABLED=false
[[ -n "$GITHUB_TOKEN" ]] && CLOUD_AGENTS_ENABLED=true
save_env

# 3f. New Relic license key (optional — enables full application monitoring)
echo
info "New Relic monitoring sends JVM and HTTP metrics to your New Relic account."
NEW_RELIC_TOKEN=""
NEW_RELIC_ENABLED=false
if [[ -n "$_existing_nr_token" ]]; then
  ask "New Relic license key [current hidden, press Enter to keep, or 'clear' to disable]: "
  read -rsp "" _nr_token_input; echo
  if [[ "$(echo "$_nr_token_input" | tr '[:upper:]' '[:lower:]')" == "clear" ]]; then
    NEW_RELIC_TOKEN=""
    info "New Relic monitoring disabled."
  elif [[ -z "$_nr_token_input" ]]; then
    NEW_RELIC_TOKEN="$_existing_nr_token"
    NEW_RELIC_ENABLED=true
    info "New Relic token kept."
  else
    NEW_RELIC_TOKEN="$_nr_token_input"
    NEW_RELIC_ENABLED=true
    success "New Relic token updated."
  fi
else
  ask "Enable New Relic monitoring? [y/N]: "
  read -r _nr_enable_input; echo
  if [[ "$(echo "$_nr_enable_input" | tr '[:upper:]' '[:lower:]')" == "y" ]]; then
    ask "New Relic license key: "
    read -rsp "" NEW_RELIC_TOKEN; echo
    if [[ -n "$NEW_RELIC_TOKEN" ]]; then
      NEW_RELIC_ENABLED=true
      success "New Relic monitoring enabled."
    else
      info "No license key entered — New Relic monitoring disabled."
    fi
  else
    info "New Relic monitoring disabled."
  fi
fi
save_env

# 3g. Admin username + password (required — creates the dashboard login account)
echo
printf '\033[1;34m─── Dashboard Admin Account ───────────────────────────────────────────\033[0m\n'
echo
info "These credentials are used to log in to the Hugin web dashboard."

_existing_admin_user=$(grep -E '^ADMIN_USERNAME=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || true)
_existing_admin_pw=$(grep -E '^ADMIN_PASSWORD=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || true)

# Username
if [[ -n "$_existing_admin_user" ]]; then
  ask "Admin username [current: ${_existing_admin_user}, press Enter to keep]: "
  read -r _admin_user_input; echo
  ADMIN_USERNAME="${_admin_user_input:-${_existing_admin_user}}"
else
  ask "Admin username [admin]: "
  read -r _admin_user_input; echo
  ADMIN_USERNAME="${_admin_user_input:-admin}"
fi
info "Admin username: $ADMIN_USERNAME"

# Password
ADMIN_PASSWORD=""
if [[ -n "$_existing_admin_pw" ]]; then
  ask "Admin password [current hidden, press Enter to keep]: "
  read -rsp "" _admin_pw_input; echo
  ADMIN_PASSWORD="${_admin_pw_input:-${_existing_admin_pw}}"
else
  while [[ -z "$ADMIN_PASSWORD" ]]; do
    ask "Admin password (min 8 characters): "
    read -rsp "" ADMIN_PASSWORD; echo
    if [[ "${#ADMIN_PASSWORD}" -lt 8 ]]; then
      warn "Password must be at least 8 characters. Try again."
      ADMIN_PASSWORD=""
    fi
  done
fi
success "Admin password set."
save_env

# 3h. JWT secret (auto-generate or enter manually)
echo
printf '\033[1;34m─── JWT Secret ────────────────────────────────────────────────────────\033[0m\n'
echo
info "The JWT secret signs dashboard session tokens. A strong random secret is recommended."
echo "  1) Auto-generate  (recommended — 48-char cryptographically random secret)"
echo "  2) Enter manually (paste your own 32+ character secret)"
echo

_existing_jwt_secret=$(grep -E '^JWT_SECRET=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || true)
_jwt_default_choice=1
[[ -n "$_existing_jwt_secret" ]] && _jwt_default_choice=1   # always offer to regenerate

ask "JWT option [${_jwt_default_choice}]: "; read -r _jwt_choice; echo
_jwt_choice="${_jwt_choice:-${_jwt_default_choice}}"

JWT_SECRET=""
case "$_jwt_choice" in
  2)
    while [[ "${#JWT_SECRET}" -lt 32 ]]; do
      ask "JWT secret (min 32 characters): "
      read -rsp "" JWT_SECRET; echo
      if [[ "${#JWT_SECRET}" -lt 32 ]]; then
        warn "Secret must be at least 32 characters. Try again."
        JWT_SECRET=""
      fi
    done
    success "JWT secret set (${#JWT_SECRET} chars)."
    ;;
  *)
    JWT_SECRET=$(python3 -c "import secrets; print(secrets.token_urlsafe(48))" 2>/dev/null \
                 || openssl rand -base64 48 2>/dev/null | tr -d '\n')
    success "JWT secret auto-generated (${#JWT_SECRET} chars)."
    ;;
esac
fi  # end SKIP_CREDENTIALS check

# ── 4. write hugin.env ────────────────────────────────────────────────────────
save_env
success "Wrote $ENV_FILE (chmod 600)"

# ── 5. write config/application.yml ──────────────────────────────────────────
cat > "$CONFIG_YML" <<EOF
# Per-installation overrides — merged on top of the bundled application.yml.
mcp:
  config-file: ${HUGIN_HOME}/config/mcp-servers.json

search:
  openrouter-script: ${HUGIN_HOME}/bin/openrouter-search-mcp.py

llm:
  model: \${LLM_MODEL:${LLM_MODEL}}

agent:
  api-key: \${AGENT_API_KEY:}
  home: ${HUGIN_HOME}
  tools:
    workspace-root: ${HUGIN_HOME}/workspace
  cloud:
    enabled: \${CLOUD_AGENTS_ENABLED:false}
    github-token: \${GITHUB_TOKEN:}
    cleanup-on-complete: true

logging:
  file:
    name: ${HUGIN_HOME}/logs/hugin.log

# Environment variables DB_URL, ADMIN_USERNAME, ADMIN_PASSWORD, JWT_SECRET
# are sourced from hugin.env and picked up by application.yml property placeholders.
EOF
info "Wrote $CONFIG_YML"

# Seed mcp-servers.json from example if not present (never overwrite an existing one)
if [[ ! -f "$MCP_JSON" ]]; then
  if [[ -f "$REPO_DIR/mcp-servers.example.json" ]]; then
    cp "$REPO_DIR/mcp-servers.example.json" "$MCP_JSON"
    info "Seeded $MCP_JSON from mcp-servers.example.json"
  else
    printf '{"mcpServers":{}}\n' > "$MCP_JSON"
    info "Created empty $MCP_JSON"
  fi
else
  info "$MCP_JSON already exists — not overwritten."
fi

# ── 6. python venv for web search ────────────────────────────────────────────
info "Setting up Python venv for the web-search MCP server..."
python3 -m venv "$HUGIN_HOME/venv"
"$HUGIN_HOME/venv/bin/pip" install --no-cache-dir --quiet mcp
cp "$REPO_DIR/openrouter-search-mcp.py" "$HUGIN_HOME/bin/openrouter-search-mcp.py"
info "Python venv ready; openrouter-search-mcp.py copied."

# ── 7. build fat jars ────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" == "true" ]]; then
  info "Skipping Maven build (reconfigure-only mode)."
else
  info "Building fat jars — this may take a few minutes..."
  MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m}" \
    mvn -f "$REPO_DIR/pom.xml" \
        -pl mcp-integration,agent-terminal,agent-discord -am \
        clean package -DskipTests -q
  cp "$REPO_DIR"/mcp-integration/target/mcp-integration-*.jar  "$HUGIN_HOME/bin/mcp-integration.jar"
  cp "$REPO_DIR"/agent-terminal/target/agent-terminal-*.jar     "$HUGIN_HOME/bin/agent-terminal.jar"
  cp "$REPO_DIR"/agent-discord/target/agent-discord-*.jar       "$HUGIN_HOME/bin/agent-discord.jar"
  success "Jars built and copied to $HUGIN_HOME/bin/"
fi

# ── 8. install the hugin launcher ────────────────────────────────────────────
if [[ "$_force_reinstall" == "true" ]]; then
  info "Skipping launcher reinstall (paths unchanged between updates)."
else
info "Installing $LAUNCHER_NAME launcher to $LAUNCHER_PATH..."
_launcher_tmp=$(mktemp)
cat > "$_launcher_tmp" <<'LAUNCHER_EOF'
#!/usr/bin/env bash
# hugin — Hugin agent launcher (installed by install.sh)
#
# Commands:
#   hugin [run]    start service if needed, open terminal chat
#   hugin serve    run server in the foreground (no service manager)
#   hugin start / stop / restart / status / logs
#   hugin config   re-prompt for credentials, restart service
#   hugin doctor   health-check every subsystem; auto-fix what it can
#   hugin uninstall
set -euo pipefail

HUGIN_HOME="${HUGIN_HOME:-__HUGIN_HOME__}"
ENV_FILE="$HUGIN_HOME/hugin.env"
CONFIG_YML="$HUGIN_HOME/config/application.yml"
SERVICE_NAME="__SERVICE_NAME__"
LAUNCHER_PATH="__LAUNCHER_PATH__"
REPO_DIR="__REPO_DIR__"

info()    { printf '\033[1;34m[hugin]\033[0m %s\n' "$*"; }
success() { printf '\033[1;32m[hugin]\033[0m %s\n' "$*"; }
warn()    { printf '\033[1;33m[hugin]\033[0m %s\n' "$*"; }
die()     { printf '\033[1;31m[hugin]\033[0m %s\n' "$*" >&2; exit 1; }

# ── OS detection + service helpers ───────────────────────────────────────────
OS_TYPE="linux"
if [[ "$(uname -s)" == "Darwin" ]]; then
  OS_TYPE="macos"
fi

if [[ "$OS_TYPE" == "macos" ]]; then
  PLIST_LABEL="com.hugin.agent"
  PLIST_PATH="$HOME/Library/LaunchAgents/${PLIST_LABEL}.plist"

  svc_start()     { launchctl start "$PLIST_LABEL"; }
  svc_stop()      { launchctl stop  "$PLIST_LABEL" 2>/dev/null || true; }
  svc_restart()   { svc_stop; sleep 1; svc_start; }
  svc_status()    { launchctl list "$PLIST_LABEL" 2>/dev/null || echo "not loaded"; }
  svc_logs()      { exec tail -f "$HUGIN_HOME/logs/hugin.log"; }
  svc_is_active() { launchctl list 2>/dev/null | grep -q "$PLIST_LABEL"; }
  svc_is_enabled(){ [[ -f "$PLIST_PATH" ]]; }
  svc_enable()    { launchctl load -w "$PLIST_PATH" 2>/dev/null || true; }

  svc_uninstall() {
    launchctl unload "$PLIST_PATH" 2>/dev/null || true
    rm -f "$PLIST_PATH"
  }

  DISCORD_PLIST_LABEL="com.hugin.discord"
  DISCORD_PLIST_PATH="$HOME/Library/LaunchAgents/${DISCORD_PLIST_LABEL}.plist"

  discord_has_token() { grep -qE '^DISCORD_BOT_TOKEN=.+' "${ENV_FILE:-$HUGIN_HOME/hugin.env}" 2>/dev/null; }
  discord_svc_is_active() { launchctl list 2>/dev/null | grep -q "$DISCORD_PLIST_LABEL"; }
  discord_svc_stop()      { launchctl stop "$DISCORD_PLIST_LABEL" 2>/dev/null || true; }
  discord_svc_start()     { discord_has_token && launchctl start "$DISCORD_PLIST_LABEL" 2>/dev/null || true; }
  discord_svc_restart()   { discord_svc_stop; sleep 1; discord_svc_start; }

  pkg_install_redis()  { brew install redis; }
  svc_start_redis()    { brew services start redis; }
  svc_is_active_redis(){ brew services list 2>/dev/null | grep -E '^redis\s' | grep -q started; }

else
  svc_start()     { sudo systemctl start   "$SERVICE_NAME"; }
  svc_stop()      { sudo systemctl stop    "$SERVICE_NAME"; }
  svc_restart()   { sudo systemctl restart "$SERVICE_NAME"; }
  svc_status()    { systemctl status       "$SERVICE_NAME"; }
  svc_logs()      { exec journalctl -u "$SERVICE_NAME" -f; }
  svc_is_active() { systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; }
  svc_is_enabled(){ systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; }
  svc_enable()    { sudo systemctl enable "$SERVICE_NAME"; }

  svc_uninstall() {
    sudo systemctl disable --now "$SERVICE_NAME" 2>/dev/null || true
    sudo rm -f "/etc/systemd/system/${SERVICE_NAME}.service"
    sudo systemctl daemon-reload
  }

  discord_has_token() { grep -qE '^DISCORD_BOT_TOKEN=.+' "${ENV_FILE:-$HUGIN_HOME/hugin.env}" 2>/dev/null; }
  discord_svc_is_active() { systemctl is-active --quiet hugin-discord 2>/dev/null; }
  discord_svc_stop()      { sudo systemctl stop hugin-discord 2>/dev/null || true; }
  discord_svc_start()     { discord_has_token && sudo systemctl start hugin-discord 2>/dev/null || true; }
  discord_svc_restart()   { sudo systemctl restart hugin-discord 2>/dev/null || true; }

  pkg_install_redis()  { sudo apt-get install -y redis-server; }
  svc_start_redis()    { sudo systemctl enable --now redis-server; }
  svc_is_active_redis(){ systemctl is-active --quiet redis-server 2>/dev/null; }
fi

load_env() {
  [[ -f "$ENV_FILE" ]] || return 0
  # shellcheck disable=SC2046
  export $(grep -v '^\s*#' "$ENV_FILE" | grep -v '^\s*$' | xargs)
}

wait_for_health() {
  local max="${1:-45}" elapsed=0
  until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
    elapsed=$((elapsed + 1))
    [[ $elapsed -ge $max ]] && { warn "Server did not respond within ${max}s. Try: hugin logs"; return 1; }
    sleep 1
  done
}

cmd_run() {
  load_env
  if ! svc_is_active 2>/dev/null; then
    info "Starting $SERVICE_NAME service..."
    svc_start
  fi
  info "Waiting for agent server..."
  if wait_for_health 45; then
    success "Server ready at http://localhost:8080"
  fi
  export AGENT_SERVER_URL="http://localhost:8080"
  [[ -n "${AGENT_API_KEY:-}" ]] && export AGENT_API_KEY
  exec java -jar "$HUGIN_HOME/bin/agent-terminal.jar"
}

cmd_serve() {
  load_env
  exec java -jar "$HUGIN_HOME/bin/mcp-integration.jar" \
    "--spring.config.additional-location=file:${CONFIG_YML}"
}

cmd_start()   { svc_start; }
cmd_stop()    { svc_stop; }
cmd_restart() { svc_restart; }
cmd_status()  { svc_status; }
cmd_logs()    { svc_logs; }

cmd_config() {
  [[ -f "$ENV_FILE" ]] && load_env
  local new_key=""
  while [[ -z "$new_key" ]]; do
    printf '\033[1;35m   >\033[0m OpenRouter API key [current hidden, Enter to keep]: '
    read -rsp "" new_key; echo
    [[ -z "$new_key" ]] && new_key="${OPEN_ROUTER_API_KEY:-}"
    [[ -z "$new_key" ]] && warn "Key is required."
  done

  echo "  1) None   — disable long-term memory"
  echo "  2) Local  — install/start Redis on this machine"
  echo "  3) Host   — connect to an existing Redis host"
  echo
  local _cur_choice=1
  if [[ "${MEMORY_ENABLED:-false}" == "true" ]]; then
    if [[ "${REDIS_HOST:-}" == "localhost" || "${REDIS_HOST:-}" == "127.0.0.1" ]]; then
      _cur_choice=2
    else
      _cur_choice=3
    fi
  fi
  printf '\033[1;35m   >\033[0m Redis option [%s]: ' "$_cur_choice"
  read -r _redis_choice; echo
  _redis_choice="${_redis_choice:-${_cur_choice}}"

  local mem=false rhost="" rport=6379
  case "$_redis_choice" in
    2)
      rhost="localhost"
      printf '\033[1;35m   >\033[0m Redis port [6379]: '
      read -r rport_in; echo
      rport="${rport_in:-6379}"
      if ! command -v redis-server >/dev/null 2>&1; then
        info "Installing Redis..."
        pkg_install_redis
      fi
      if ! svc_is_active_redis 2>/dev/null; then
        svc_start_redis
      fi
      if command -v redis-cli >/dev/null 2>&1 && redis-cli -h "$rhost" -p "$rport" ping >/dev/null 2>&1; then
        mem=true
        success "Local Redis reachable — long-term memory enabled."
      else
        warn "Local Redis not reachable — memory left disabled."
        rhost=""
      fi
      ;;
    3)
      printf '\033[1;35m   >\033[0m Redis host%s: ' "${REDIS_HOST:+ [current: ${REDIS_HOST}]}"
      read -r redis_host_in; echo
      rhost="${redis_host_in:-${REDIS_HOST:-}}"
      printf '\033[1;35m   >\033[0m Redis port [%s]: ' "${REDIS_PORT:-6379}"
      read -r rport_in; echo
      rport="${rport_in:-${REDIS_PORT:-6379}}"
      if [[ -n "$rhost" ]] && command -v redis-cli >/dev/null 2>&1 \
          && redis-cli -h "$rhost" -p "$rport" ping >/dev/null 2>&1; then
        mem=true
        success "Redis reachable — long-term memory enabled."
      else
        warn "Redis not reachable — memory left disabled."
        rhost=""
      fi
      ;;
    *)
      info "Long-term memory disabled."
      ;;
  esac
  cat > "$ENV_FILE" <<ENV
OPEN_ROUTER_API_KEY=${new_key}
LLM_MODEL=${LLM_MODEL:-openai/gpt-oss-120b}
AGENT_API_KEY=${AGENT_API_KEY:-}
MEMORY_ENABLED=${mem}
REDIS_HOST=${rhost}
REDIS_PORT=${rport}
CLOUD_AGENTS_ENABLED=${CLOUD_AGENTS_ENABLED:-false}
GITHUB_TOKEN=${GITHUB_TOKEN:-}
NEW_RELIC_TOKEN=${NEW_RELIC_TOKEN:-}
NEW_RELIC_ENABLED=${NEW_RELIC_ENABLED:-false}
AGENT_HOME=${HUGIN_HOME}
DB_URL=jdbc:h2:file:${HUGIN_HOME}/db/hugin
ADMIN_USERNAME=${ADMIN_USERNAME:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-}
JWT_SECRET=${JWT_SECRET:-}
ENV
  chmod 600 "$ENV_FILE"
  success "hugin.env updated."
  if svc_is_active 2>/dev/null; then
    info "Restarting service..."
    svc_restart
    wait_for_health 45 && success "Service restarted and healthy."
  fi
}

# ── doctor ────────────────────────────────────────────────────────────────────
cmd_doctor() {
  load_env

  local _fixes=0 _fails=0

  _dr_pass()  { printf '  \033[1;32m✓\033[0m %s\n' "$*"; }
  _dr_fail()  { printf '  \033[1;31m✗\033[0m %s\n' "$*"; _fails=$((_fails + 1)); }
  _dr_fixed() { printf '  \033[1;34m→ [fixed]\033[0m %s\n' "$*"; _fixes=$((_fixes + 1)); }
  _dr_note()  { printf '  \033[1;33m⚠\033[0m %s\n' "$*"; }

  printf '\033[1;34m─── Hugin Doctor ───────────────────────────────────────────────────────\033[0m\n'
  echo

  # ── Runtime ──────────────────────────────────────────────────────────────────
  printf '\033[1;34m[Runtime]\033[0m\n'

  if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'; then
    local _jv; _jv=$(java -version 2>&1 | head -1)
    _dr_pass "Java 21+  ($_jv)"
  else
    _dr_fail "Java 21+ not found — required to run jars; re-run install.sh to install"
  fi

  if [[ -x "$HUGIN_HOME/venv/bin/python3" ]]; then
    if "$HUGIN_HOME/venv/bin/python3" -c "import mcp" 2>/dev/null; then
      _dr_pass "Python venv + mcp package"
    else
      _dr_fail "mcp package missing from venv — attempting reinstall"
      if "$HUGIN_HOME/venv/bin/pip" install --no-cache-dir --quiet mcp 2>/dev/null; then
        _dr_fixed "mcp package reinstalled in venv"
      else
        _dr_fail "Could not reinstall mcp (check internet access)"
      fi
    fi
  else
    _dr_fail "Python venv missing at $HUGIN_HOME/venv — attempting rebuild"
    if command -v python3 >/dev/null 2>&1; then
      if python3 -m venv "$HUGIN_HOME/venv" \
          && "$HUGIN_HOME/venv/bin/pip" install --no-cache-dir --quiet mcp 2>/dev/null; then
        _dr_fixed "Python venv rebuilt"
      else
        if [[ "$OS_TYPE" == "macos" ]]; then
          _dr_fail "venv rebuild failed — check: brew install python3"
        else
          _dr_fail "venv rebuild failed — check python3-venv: sudo apt-get install python3-venv"
        fi
      fi
    else
      if [[ "$OS_TYPE" == "macos" ]]; then
        _dr_fail "python3 not found — run: brew install python3"
      else
        _dr_fail "python3 not found — run: sudo apt-get install python3 python3-venv"
      fi
    fi
  fi

  echo
  # ── Files ────────────────────────────────────────────────────────────────────
  printf '\033[1;34m[Files]\033[0m\n'

  for _d in bin config venv workspace logs; do
    if [[ -d "$HUGIN_HOME/$_d" ]]; then
      _dr_pass "~/.hugin/$_d/"
    else
      mkdir -p "$HUGIN_HOME/$_d"
      _dr_fixed "Created missing directory ~/.hugin/$_d/"
    fi
  done

  if [[ -f "$ENV_FILE" ]]; then
    local _perms; _perms=$(stat -c '%a' "$ENV_FILE" 2>/dev/null \
                           || stat -f '%OLp' "$ENV_FILE" 2>/dev/null \
                           || echo "unknown")
    if [[ "$_perms" == "600" ]]; then
      _dr_pass "hugin.env (permissions 600)"
    else
      chmod 600 "$ENV_FILE"
      _dr_fixed "Fixed hugin.env permissions ($_perms → 600)"
    fi
    if grep -qE '^OPEN_ROUTER_API_KEY=.+' "$ENV_FILE" 2>/dev/null; then
      _dr_pass "OPEN_ROUTER_API_KEY is set"
    else
      _dr_fail "OPEN_ROUTER_API_KEY is missing or empty — run: hugin config"
    fi
  else
    _dr_fail "hugin.env not found at $ENV_FILE — run: hugin config  or re-run install.sh"
  fi

  if [[ -f "$CONFIG_YML" ]]; then
    _dr_pass "config/application.yml"
  else
    _dr_fail "config/application.yml not found — re-run install.sh"
  fi

  if [[ -f "$HUGIN_HOME/config/mcp-servers.json" ]]; then
    _dr_pass "config/mcp-servers.json"
  else
    printf '{"mcpServers":{}}\n' > "$HUGIN_HOME/config/mcp-servers.json"
    _dr_fixed "Created empty mcp-servers.json"
  fi

  if [[ -f "$HUGIN_HOME/bin/mcp-integration.jar" ]]; then
    local _sz; _sz=$(du -sh "$HUGIN_HOME/bin/mcp-integration.jar" | cut -f1)
    _dr_pass "mcp-integration.jar ($_sz)"
  else
    _dr_fail "mcp-integration.jar not found — rebuild: mvn clean package -DskipTests && re-run install.sh"
  fi

  if [[ -f "$HUGIN_HOME/bin/agent-terminal.jar" ]]; then
    _dr_pass "agent-terminal.jar"
  else
    _dr_fail "agent-terminal.jar not found — rebuild: mvn clean package -DskipTests && re-run install.sh"
  fi

  if [[ -f "$HUGIN_HOME/bin/openrouter-search-mcp.py" ]]; then
    _dr_pass "openrouter-search-mcp.py"
  else
    _dr_fail "openrouter-search-mcp.py missing — re-run install.sh to restore it"
  fi

  echo
  # ── Service ──────────────────────────────────────────────────────────────────
  printf '\033[1;34m[Service]\033[0m\n'

  if [[ "$OS_TYPE" == "macos" ]]; then
    if [[ -f "$PLIST_PATH" ]]; then
      _dr_pass "LaunchAgent plist present ($PLIST_PATH)"
    else
      _dr_fail "LaunchAgent plist not found at $PLIST_PATH — re-run install.sh"
    fi
  else
    if [[ -f "/etc/systemd/system/${SERVICE_NAME}.service" ]]; then
      _dr_pass "systemd unit file present"
    else
      _dr_fail "systemd unit not found at /etc/systemd/system/${SERVICE_NAME}.service — re-run install.sh"
    fi

    if svc_is_enabled 2>/dev/null; then
      _dr_pass "service enabled (starts on boot)"
    else
      if svc_enable 2>/dev/null; then
        _dr_fixed "Enabled $SERVICE_NAME for boot"
      else
        _dr_fail "Could not enable service — unit file may be missing"
      fi
    fi
  fi

  if svc_is_active 2>/dev/null; then
    _dr_pass "service is running"
  else
    info "Service not running — attempting to start..."
    if svc_start 2>/dev/null; then
      sleep 3
      if svc_is_active 2>/dev/null; then
        _dr_fixed "Service started successfully"
      else
        _dr_fail "Service started but exited — check: hugin logs"
      fi
    else
      _dr_fail "Could not start service — check: hugin logs"
    fi
  fi

  echo
  # ── Network ──────────────────────────────────────────────────────────────────
  printf '\033[1;34m[Network]\033[0m\n'

  if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
    local _hj; _hj=$(curl -s http://localhost:8080/actuator/health \
                     | grep -o '"status":"[^"]*"' | head -1)
    _dr_pass "HTTP health endpoint ($_hj)"
  else
    local _hw=20 _he=0
    _dr_note "Health endpoint not yet responding — waiting up to ${_hw}s..."
    while ! curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
      _he=$((_he + 1)); [[ $_he -ge $_hw ]] && break; sleep 1
    done
    if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
      _dr_fixed "Agent server is now healthy"
    else
      _dr_fail "Agent server not responding on :8080"
      if command -v ss >/dev/null 2>&1; then
        local _conflict; _conflict=$(ss -tlnp 2>/dev/null | grep ':8080 ' | head -1 || true)
        [[ -n "$_conflict" ]] && _dr_fail "Port 8080 in use by another process: $_conflict"
      elif command -v lsof >/dev/null 2>&1; then
        local _conflict; _conflict=$(lsof -iTCP:8080 -sTCP:LISTEN 2>/dev/null | tail -1 || true)
        [[ -n "$_conflict" ]] && _dr_fail "Port 8080 in use by another process: $_conflict"
      fi
      _dr_note "Tip: inspect with  hugin logs"
    fi
  fi

  echo
  # ── Memory ───────────────────────────────────────────────────────────────────
  printf '\033[1;34m[Memory]\033[0m\n'

  if [[ "${MEMORY_ENABLED:-false}" == "true" ]]; then
    local _rh="${REDIS_HOST:-localhost}" _rp="${REDIS_PORT:-6379}"
    if command -v redis-cli >/dev/null 2>&1 \
        && redis-cli -h "$_rh" -p "$_rp" ping >/dev/null 2>&1; then
      _dr_pass "Redis reachable at $_rh:$_rp  (long-term memory active)"
    else
      _dr_fail "Redis not reachable at $_rh:$_rp  (MEMORY_ENABLED=true)"
      if [[ "$_rh" == "localhost" || "$_rh" == "127.0.0.1" ]]; then
        if command -v redis-server >/dev/null 2>&1; then
          if svc_start_redis 2>/dev/null; then
            sleep 1
            if redis-cli -h "$_rh" -p "$_rp" ping >/dev/null 2>&1; then
              _dr_fixed "Started local redis and confirmed reachable"
            else
              _dr_fail "Redis started but still not responding on $_rh:$_rp"
            fi
          else
            if [[ "$OS_TYPE" == "macos" ]]; then
              _dr_fail "Could not start Redis — check: brew services list"
            else
              _dr_fail "Could not start redis-server — check: systemctl status redis-server"
            fi
          fi
        else
          if [[ "$OS_TYPE" == "macos" ]]; then
            _dr_fail "redis-server not installed — run: brew install redis"
          else
            _dr_fail "redis-server not installed — run: sudo apt-get install redis-server"
          fi
        fi
      else
        _dr_fail "Remote Redis at $_rh:$_rp is unreachable — check host, port, and firewall"
      fi
    fi
  else
    _dr_note "Long-term memory disabled (MEMORY_ENABLED=false) — Redis not checked"
  fi

  echo
  # ── Summary ──────────────────────────────────────────────────────────────────
  printf '\033[1;34m────────────────────────────────────────────────────────────────────────\033[0m\n'
  if [[ $_fails -eq 0 ]]; then
    if [[ $_fixes -gt 0 ]]; then
      success "All checks passed after $_fixes auto-fix(es). Hugin is healthy."
    else
      success "All checks passed. Hugin is healthy."
    fi
  else
    warn "$_fails check(s) failed, $_fixes item(s) auto-fixed."
    warn "Address the failures above, then re-run:  hugin doctor"
    return 1
  fi
}

cmd_update() {
  if [[ ! -d "$REPO_DIR/.git" ]]; then
    die "Repo not found at $REPO_DIR — cannot update. Re-run install.sh from the source directory."
  fi

  info "Pulling latest code from $REPO_DIR..."
  git -C "$REPO_DIR" pull --ff-only || die "git pull failed — resolve conflicts manually and retry."

  info "Building jars (this may take a minute)..."
  MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m}" \
    mvn -f "$REPO_DIR/pom.xml" \
        -pl mcp-integration,agent-terminal,agent-discord -am \
        clean package -DskipTests -q \
    || die "Maven build failed — check output above."

  local was_running=false
  if svc_is_active 2>/dev/null; then
    was_running=true
    info "Stopping service to swap jars..."
    svc_stop
    sleep 1
  fi

  local discord_was_running=false
  if discord_svc_is_active 2>/dev/null; then
    discord_was_running=true
    info "Stopping Discord bot service to swap jar..."
    discord_svc_stop
    sleep 1
  fi

  cp "$REPO_DIR"/mcp-integration/target/mcp-integration-*.jar  "$HUGIN_HOME/bin/mcp-integration.jar"
  cp "$REPO_DIR"/agent-terminal/target/agent-terminal-*.jar     "$HUGIN_HOME/bin/agent-terminal.jar"
  cp "$REPO_DIR"/agent-discord/target/agent-discord-*.jar       "$HUGIN_HOME/bin/agent-discord.jar" 2>/dev/null || true
  cp "$REPO_DIR/openrouter-search-mcp.py"                       "$HUGIN_HOME/bin/openrouter-search-mcp.py"
  success "Jars and scripts updated."

  if [[ "$was_running" == "true" ]]; then
    info "Restarting service..."
    svc_start
    local elapsed=0
    until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
      elapsed=$((elapsed + 1))
      [[ $elapsed -ge 60 ]] && { warn "Server did not respond within 60s. Try: hugin logs"; return 1; }
      sleep 1
    done
    success "Service restarted and healthy."
  else
    info "Service was not running — start it with: hugin start"
  fi

  if [[ "$discord_was_running" == "true" ]]; then
    info "Restarting Discord bot service..."
    discord_svc_start
    success "Discord bot service restarted."
  elif discord_has_token && [[ -f "$HUGIN_HOME/bin/agent-discord.jar" ]]; then
    info "Discord bot was not running — start it with: hugin discord start"
  fi
}

cmd_uninstall() {
  info "Stopping and removing $SERVICE_NAME service..."
  svc_uninstall
  sudo_retry --reason "remove launcher from $LAUNCHER_PATH" rm -f "$LAUNCHER_PATH"
  success "Service and launcher removed."
  if [[ -d "$HUGIN_HOME" ]]; then
    printf '\033[1;35m   >\033[0m Delete %s? [y/N] ' "$HUGIN_HOME"
    read -r _c; echo
    if [[ "$(echo "$_c" | tr '[:upper:]' '[:lower:]')" == "y" ]]; then
      rm -rf "$HUGIN_HOME"; success "$HUGIN_HOME deleted."
    else
      info "Kept $HUGIN_HOME."
    fi
  fi
}

CMD="${1:-run}"
shift || true
case "$CMD" in
  run)       cmd_run       ;;
  serve)     cmd_serve     ;;
  start)     cmd_start     ;;
  stop)      cmd_stop      ;;
  restart)   cmd_restart   ;;
  status)    cmd_status    ;;
  logs)      cmd_logs      ;;
  config)    cmd_config    ;;
  update)    cmd_update    ;;
  doctor)    cmd_doctor    ;;
  uninstall) cmd_uninstall ;;
  *)
    cat <<USAGE
Usage: hugin [command]

  (none) / run   Start service if needed, open terminal chat
  serve          Run the server in the foreground (no service manager)
  start          Start the background service
  stop           Stop the background service
  restart        Restart the background service
  status         Show service status
  logs           Stream service logs
  config         Reconfigure credentials, restart service
  update         Pull latest code, rebuild jars, restart service
  doctor         Check every subsystem; auto-fix what it can
  uninstall      Remove service, launcher, and optionally ~/.hugin
USAGE
    exit 1
    ;;
esac
LAUNCHER_EOF

# Substitute the install-time paths into the temp file, then copy to final location
SED_INPLACE \
  -e "s|__HUGIN_HOME__|${HUGIN_HOME}|g" \
  -e "s|__SERVICE_NAME__|${SERVICE_NAME}|g" \
  -e "s|__LAUNCHER_PATH__|${LAUNCHER_PATH}|g" \
  -e "s|__REPO_DIR__|${REPO_DIR}|g" \
  "$_launcher_tmp"
sudo_retry --reason "install launcher to $LAUNCHER_PATH" cp "$_launcher_tmp" "$LAUNCHER_PATH"
rm -f "$_launcher_tmp"
sudo_retry --reason "make launcher executable" chmod 0755 "$LAUNCHER_PATH"
success "Launcher installed: $LAUNCHER_PATH"
fi  # end skip-on-reinstall

# ── 9. install and start service ─────────────────────────────────────────────
if [[ "$_force_reinstall" == "false" ]]; then
  info "Installing $SERVICE_NAME service..."
  svc_install
fi

if svc_is_active 2>/dev/null; then
  info "Restarting $SERVICE_NAME service..."
  svc_restart
else
  if [[ "$_force_reinstall" == "false" ]]; then
    svc_enable 2>/dev/null || true
  fi
  svc_start 2>/dev/null || true
  info "Service started."
fi

# ── 9b. Discord bot service ───────────────────────────────────────────────────
info "Setting up Discord bot service..."
discord_svc_install

# ── 10. health check ──────────────────────────────────────────────────────────
wait_for_health 60

# ── done ──────────────────────────────────────────────────────────────────────
echo
printf '\033[1;32m══════════════════════════════════════════════════════════════════\033[0m\n'
printf '\033[1;32m  Hugin is running at http://localhost:8080\033[0m\n'
printf '\033[1;32m══════════════════════════════════════════════════════════════════\033[0m\n'
echo
cat <<MSG
  Dashboard:            http://localhost:8080
  Start chatting:       hugin
  Server in foreground: hugin serve
  Service status/logs:  hugin status  |  hugin logs
  Health check:         hugin doctor
  Reconfigure:          hugin config
  Update (rebuild):     hugin update
  Workspace:            $HUGIN_HOME/workspace
  Config:               $HUGIN_HOME/config/
  Env vars:             $ENV_FILE
  Uninstall:            hugin uninstall
MSG
echo
