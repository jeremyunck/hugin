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
SERVICE_FILE="/etc/systemd/system/${SERVICE_NAME}.service"
LAUNCHER_PATH="/usr/local/bin/${LAUNCHER_NAME}"

# ── colours ───────────────────────────────────────────────────────────────────
info()    { printf '\033[1;34m[hugin]\033[0m %s\n' "$*"; }
success() { printf '\033[1;32m[hugin]\033[0m %s\n' "$*"; }
warn()    { printf '\033[1;33m[hugin]\033[0m %s\n' "$*"; }
die()     { printf '\033[1;31m[hugin]\033[0m %s\n' "$*" >&2; exit 1; }
ask()     { printf '\033[1;35m   >\033[0m %s' "$*"; }

require_cmd() { command -v "$1" >/dev/null 2>&1; }

# ── wait for health ───────────────────────────────────────────────────────────
wait_for_health() {
  local max="${1:-45}" elapsed=0
  info "Waiting for agent server to become healthy (up to ${max}s)..."
  until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
    elapsed=$((elapsed + 1))
    if [[ $elapsed -ge $max ]]; then
      warn "Server did not become healthy within ${max}s."
      warn "Inspect logs:  sudo journalctl -u $SERVICE_NAME -n 50"
      return 1
    fi
    sleep 1
  done
  success "Server healthy at http://localhost:8080"
}

# ── uninstall ─────────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--uninstall" ]]; then
  info "Stopping and removing $SERVICE_NAME service..."
  sudo systemctl disable --now "$SERVICE_NAME" 2>/dev/null || true
  sudo rm -f "$SERVICE_FILE"
  sudo systemctl daemon-reload
  sudo rm -f "$LAUNCHER_PATH"
  success "Service and launcher removed."

  if [[ -d "$HUGIN_HOME" ]]; then
    ask "Delete $HUGIN_HOME (config + workspace + logs)? [y/N] "; read -r _confirm; echo
    if [[ "${_confirm,,}" == "y" ]]; then
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

if [[ -f "$HUGIN_HOME/bin/mcp-integration.jar" ]]; then
  ALREADY_INSTALLED=true
  warn "Existing Hugin installation detected at $HUGIN_HOME."
  echo
  echo "  1) Reconfigure only  (keep existing jars, update env + config, restart service)"
  echo "  2) Full reinstall    (rebuild jars from source, update everything)"
  echo
  ask "Choice [1]: "; read -r _choice; echo
  _choice="${_choice:-1}"
  if [[ "$_choice" == "1" ]]; then
    SKIP_BUILD=true
    info "Will reconfigure without rebuilding."
  else
    info "Full reinstall selected — jars will be rebuilt."
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
need_apt_update=false
pkgs_to_install=()

# Java 21
java_ok=false
if require_cmd java && java -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'; then
  info "Java 21+ already present."
  java_ok=true
fi

if [[ "$java_ok" == "false" ]]; then
  warn "Java 21 not found — will install Temurin JDK 21 via Adoptium apt repo."
  sudo install -d -m 0755 /etc/apt/keyrings
  wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  # shellcheck source=/dev/null
  . /etc/os-release
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] \
https://packages.adoptium.net/artifactory/deb ${VERSION_CODENAME} main" \
    | sudo tee /etc/apt/sources.list.d/adoptium.list >/dev/null
  need_apt_update=true
  pkgs_to_install+=(temurin-21-jdk)
fi

require_cmd git     || pkgs_to_install+=(git)
require_cmd mvn     || pkgs_to_install+=(maven)
require_cmd python3 || pkgs_to_install+=(python3)
require_cmd curl    || pkgs_to_install+=(curl)

if ! python3 -c "import venv" 2>/dev/null; then
  pkgs_to_install+=(python3-venv)
fi

if [[ "$need_apt_update" == "true" ]] || [[ ${#pkgs_to_install[@]} -gt 0 ]]; then
  sudo apt-get update -qq
fi

if [[ ${#pkgs_to_install[@]} -gt 0 ]]; then
  info "Installing system packages: ${pkgs_to_install[*]}"
  sudo apt-get install -y "${pkgs_to_install[@]}"
fi

# ── 3. prompts ────────────────────────────────────────────────────────────────
echo
printf '\033[1;34m─── Environment Configuration ─────────────────────────────────────────\033[0m\n'
echo

# Load existing values so they can be used as defaults on reconfigure
_existing_key="" _existing_agent_key="" _existing_redis_host="" _existing_redis_port="6379"
_existing_model="" _existing_github_token=""
if [[ -f "$ENV_FILE" ]]; then
  _existing_key=$(grep -E '^OPEN_ROUTER_API_KEY=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_agent_key=$(grep -E '^AGENT_API_KEY=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_redis_host=$(grep -E '^REDIS_HOST=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_redis_port=$(grep -E '^REDIS_PORT=' "$ENV_FILE" | cut -d= -f2- || echo "6379")
  _existing_model=$(grep -E '^LLM_MODEL=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_github_token=$(grep -E '^GITHUB_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)
fi

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

# 3b. LLM model (optional)
echo
info "Default model: openai/gpt-oss-120b (OpenRouter)"
ask "LLM model override [press Enter for default${_existing_model:+, current: $_existing_model}]: "
read -r LLM_MODEL_INPUT; echo
LLM_MODEL="${LLM_MODEL_INPUT:-${_existing_model:-openai/gpt-oss-120b}}"
info "Model: $LLM_MODEL"

# 3c. Agent API key (optional — secures /api/agent/** with X-API-Key header)
echo
info "Agent API key: if set, all /api/agent/** calls require  X-API-Key: <value>"
if [[ -n "$_existing_agent_key" ]]; then
  ask "Agent API key [current hidden, press Enter to keep, or type 'clear' to remove]: "
  read -rsp "" _agent_key_input; echo
  if [[ "${_agent_key_input,,}" == "clear" ]]; then
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
      info "Redis not found locally — installing redis-server..."
      sudo apt-get install -y redis-server
    fi

    if ! systemctl is-active --quiet redis-server 2>/dev/null; then
      info "Starting redis-server..."
      sudo systemctl enable --now redis-server
    else
      info "Local Redis already running."
    fi

    # Test connectivity
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

# 3e. GitHub token (optional — for cloud-agent repo cloning)
echo
info "GitHub token enables the cloud-agent feature (/api/agents — repo clone + agent loop)."
if [[ -n "$_existing_github_token" ]]; then
  ask "GitHub personal-access token [current hidden, press Enter to keep, or 'clear' to remove]: "
  read -rsp "" _gh_token_input; echo
  if [[ "${_gh_token_input,,}" == "clear" ]]; then
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

# ── 4. write hugin.env ────────────────────────────────────────────────────────
cat > "$ENV_FILE" <<EOF
# Hugin environment — sourced by the systemd service and the hugin launcher.
# Permissions: 600 (owner-read-only).  Do not commit this file.

OPEN_ROUTER_API_KEY=${OPENROUTER_KEY}
LLM_MODEL=${LLM_MODEL}

# Secure the /api/agent/** endpoints with an X-API-Key header (leave blank to disable).
AGENT_API_KEY=${AGENT_API_KEY}

# Long-term Redis-backed semantic memory
MEMORY_ENABLED=${MEMORY_ENABLED}
REDIS_HOST=${REDIS_HOST_VAL}
REDIS_PORT=${REDIS_PORT_VAL}

# Cloud-agent feature (POST /api/agents — clone repo, run agent loop)
CLOUD_AGENTS_ENABLED=${CLOUD_AGENTS_ENABLED}
GITHUB_TOKEN=${GITHUB_TOKEN}

# Hugin home directory (workspace root is $HUGIN_HOME/workspace)
AGENT_HOME=${HUGIN_HOME}
EOF
chmod 600 "$ENV_FILE"
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
        -pl mcp-integration,agent-terminal -am \
        clean package -DskipTests -q
  cp "$REPO_DIR"/mcp-integration/target/mcp-integration-*.jar  "$HUGIN_HOME/bin/mcp-integration.jar"
  cp "$REPO_DIR"/agent-terminal/target/agent-terminal-*.jar     "$HUGIN_HOME/bin/agent-terminal.jar"
  success "Jars built and copied to $HUGIN_HOME/bin/"
fi

# ── 8. install the hugin launcher ────────────────────────────────────────────
info "Installing $LAUNCHER_NAME launcher to $LAUNCHER_PATH..."
sudo tee "$LAUNCHER_PATH" > /dev/null <<'LAUNCHER_EOF'
#!/usr/bin/env bash
# hugin — Hugin agent launcher (installed by install.sh)
#
# Commands:
#   hugin [run]    start service if needed, open terminal chat
#   hugin serve    run server in the foreground (no systemd)
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

info()    { printf '\033[1;34m[hugin]\033[0m %s\n' "$*"; }
success() { printf '\033[1;32m[hugin]\033[0m %s\n' "$*"; }
warn()    { printf '\033[1;33m[hugin]\033[0m %s\n' "$*"; }
die()     { printf '\033[1;31m[hugin]\033[0m %s\n' "$*" >&2; exit 1; }

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
  if ! systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
    info "Starting $SERVICE_NAME service..."
    sudo systemctl start "$SERVICE_NAME"
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

cmd_start()   { sudo systemctl start   "$SERVICE_NAME"; }
cmd_stop()    { sudo systemctl stop    "$SERVICE_NAME"; }
cmd_restart() { sudo systemctl restart "$SERVICE_NAME"; }
cmd_status()  { systemctl status       "$SERVICE_NAME"; }
cmd_logs()    { exec journalctl -u "$SERVICE_NAME" -f; }

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
        info "Installing redis-server..."
        sudo apt-get install -y redis-server
      fi
      if ! systemctl is-active --quiet redis-server 2>/dev/null; then
        sudo systemctl enable --now redis-server
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
AGENT_HOME=${HUGIN_HOME}
ENV
  chmod 600 "$ENV_FILE"
  success "hugin.env updated."
  if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
    info "Restarting service..."
    sudo systemctl restart "$SERVICE_NAME"
    wait_for_health 45 && success "Service restarted and healthy."
  fi
}

# ── doctor ────────────────────────────────────────────────────────────────────
cmd_doctor() {
  load_env

  local _fixes=0 _fails=0

  # Doctor-scoped helpers (bash dynamic scoping lets these see the locals above)
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
        _dr_fail "venv rebuild failed — check python3-venv: sudo apt-get install python3-venv"
      fi
    else
      _dr_fail "python3 not found — run: sudo apt-get install python3 python3-venv"
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

  if [[ -f "/etc/systemd/system/${SERVICE_NAME}.service" ]]; then
    _dr_pass "systemd unit file present"
  else
    _dr_fail "systemd unit not found at /etc/systemd/system/${SERVICE_NAME}.service — re-run install.sh"
  fi

  if systemctl is-enabled --quiet "$SERVICE_NAME" 2>/dev/null; then
    _dr_pass "service enabled (starts on boot)"
  else
    if sudo systemctl enable "$SERVICE_NAME" 2>/dev/null; then
      _dr_fixed "Enabled $SERVICE_NAME for boot"
    else
      _dr_fail "Could not enable service — unit file may be missing"
    fi
  fi

  if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
    _dr_pass "service is running"
  else
    info "Service not running — attempting to start..."
    if sudo systemctl start "$SERVICE_NAME" 2>/dev/null; then
      sleep 3
      if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
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
      # Check for port conflict
      if command -v ss >/dev/null 2>&1; then
        local _conflict; _conflict=$(ss -tlnp 2>/dev/null | grep ':8080 ' | head -1 || true)
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
          if sudo systemctl start redis-server 2>/dev/null; then
            sleep 1
            if redis-cli -h "$_rh" -p "$_rp" ping >/dev/null 2>&1; then
              _dr_fixed "Started local redis-server and confirmed reachable"
            else
              _dr_fail "redis-server started but still not responding on $_rh:$_rp"
            fi
          else
            _dr_fail "Could not start redis-server — check: systemctl status redis-server"
          fi
        else
          _dr_fail "redis-server not installed — run: sudo apt-get install redis-server"
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

cmd_uninstall() {
  info "Stopping and removing $SERVICE_NAME service..."
  sudo systemctl disable --now "$SERVICE_NAME" 2>/dev/null || true
  sudo rm -f "/etc/systemd/system/${SERVICE_NAME}.service"
  sudo systemctl daemon-reload
  sudo rm -f "$LAUNCHER_PATH"
  success "Service and launcher removed."
  if [[ -d "$HUGIN_HOME" ]]; then
    printf '\033[1;35m   >\033[0m Delete %s? [y/N] ' "$HUGIN_HOME"
    read -r _c; echo
    if [[ "${_c,,}" == "y" ]]; then
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
  doctor)    cmd_doctor    ;;
  uninstall) cmd_uninstall ;;
  *)
    cat <<USAGE
Usage: hugin [command]

  (none) / run   Start service if needed, open terminal chat
  serve          Run the server in the foreground (no systemd)
  start          Start the background service
  stop           Stop the background service
  restart        Restart the background service
  status         Show service status
  logs           Stream service logs  (journalctl -f)
  config         Reconfigure credentials, restart service
  doctor         Check every subsystem; auto-fix what it can
  uninstall      Remove service, launcher, and optionally ~/.hugin
USAGE
    exit 1
    ;;
esac
LAUNCHER_EOF

# Substitute the install-time paths into the launcher
sudo sed -i \
  -e "s|__HUGIN_HOME__|${HUGIN_HOME}|g" \
  -e "s|__SERVICE_NAME__|${SERVICE_NAME}|g" \
  -e "s|__LAUNCHER_PATH__|${LAUNCHER_PATH}|g" \
  "$LAUNCHER_PATH"
sudo chmod 0755 "$LAUNCHER_PATH"
success "Launcher installed: $LAUNCHER_PATH"

# ── 9. install systemd service ────────────────────────────────────────────────
info "Installing systemd service $SERVICE_NAME..."
sudo tee "$SERVICE_FILE" > /dev/null <<SERVICE
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
# Prepend the venv so the web-search MCP subprocess finds python3 + mcp package.
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

sudo systemctl daemon-reload

# Stop any previous incarnation cleanly before (re)starting
if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
  info "Restarting existing $SERVICE_NAME service..."
  sudo systemctl restart "$SERVICE_NAME"
else
  sudo systemctl enable --now "$SERVICE_NAME"
  info "Service enabled and started."
fi

# ── 10. health check ──────────────────────────────────────────────────────────
wait_for_health 60

# ── done ──────────────────────────────────────────────────────────────────────
echo
printf '\033[1;32m══════════════════════════════════════════════════════════════════\033[0m\n'
printf '\033[1;32m  Hugin is running at http://localhost:8080\033[0m\n'
printf '\033[1;32m══════════════════════════════════════════════════════════════════\033[0m\n'
echo
cat <<MSG
  Start chatting:       hugin
  Server in foreground: hugin serve
  Service status/logs:  hugin status  |  hugin logs
  Health check:         hugin doctor
  Reconfigure:          hugin config
  Workspace:            $HUGIN_HOME/workspace
  Config:               $HUGIN_HOME/config/
  Env vars:             $ENV_FILE
  Uninstall:            hugin uninstall
MSG
echo
