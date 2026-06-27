#!/usr/bin/env bash
# install.sh — single-script installer for Bouw
#
# Usage:
#   ./install.sh             interactive install / reconfigure
#   ./install.sh --uninstall remove service, launcher, and optionally ~/.bouw
#
# All files go under BOUW_HOME (default ~/.bouw).
# Environment variables collected during install are stored in ~/.bouw/bouw.env (chmod 600).
# The agent workspace (file/shell operations) lives at ~/.bouw/workspace.
set -euo pipefail

# ── constants ─────────────────────────────────────────────────────────────────
BOUW_HOME="${BOUW_HOME:-$HOME/.bouw}"
SERVICE_NAME="bouw"
LAUNCHER_NAME="bouw"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_USER="$(id -un)"
ENV_FILE="$BOUW_HOME/bouw.env"
CONFIG_YML="$BOUW_HOME/config/application.yml"
AUTO_UPDATE_SCRIPT="$BOUW_HOME/bin/bouw-auto-update.sh"
AUTO_UPDATE_INTERVAL_SECS="${BOUW_AUTO_UPDATE_INTERVAL_SECS:-300}"

# ── colours ───────────────────────────────────────────────────────────────────
info()    { printf '\033[1;34m[bouw]\033[0m %s\n' "$*"; }
success() { printf '\033[1;32m[bouw]\033[0m %s\n' "$*"; }
warn()    { printf '\033[1;33m[bouw]\033[0m %s\n' "$*"; }
die()     { printf '\033[1;31m[bouw]\033[0m %s\n' "$*" >&2; exit 1; }
ask()     { printf '\033[1;35m   >\033[0m %s' "$*"; }

require_cmd() { command -v "$1" >/dev/null 2>&1; }

resolve_docker_bin() {
  local candidate
  for candidate in \
    "${BOUW_SANDBOX_DOCKER_BIN:-}" \
    "${SANDBOX_DOCKER_BIN:-}" \
    "$(command -v docker 2>/dev/null || true)" \
    "$HOME/.docker/bin/docker" \
    "/Applications/Docker.app/Contents/Resources/bin/docker"
  do
    if [[ -n "$candidate" && -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

resolve_bouw_version() {
  local package_json="$REPO_DIR/package.json"
  local version=""
  if [[ -f "$package_json" ]]; then
    version=$(grep -m1 '"version"' "$package_json" | sed -E 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' || true)
  fi
  [[ -n "$version" ]] && echo "$version" || echo "unknown"
}

resolve_bouw_source_sha() {
  if [[ -d "$REPO_DIR/.git" ]]; then
    git -C "$REPO_DIR" rev-parse HEAD 2>/dev/null || echo unknown
  else
    echo unknown
  fi
}

repo_slug_from_origin() {
  local remote
  remote="$(git -C "$REPO_DIR" remote get-url origin 2>/dev/null || true)"
  case "$remote" in
    git@github.com:*.git)
      remote="${remote#git@github.com:}"
      remote="${remote%.git}"
      ;;
    git@github.com:*)
      remote="${remote#git@github.com:}"
      ;;
    https://github.com/*.git)
      remote="${remote#https://github.com/}"
      remote="${remote%.git}"
      ;;
    https://github.com/*)
      remote="${remote#https://github.com/}"
      ;;
    *)
      return 1
      ;;
  esac
  [[ -n "$remote" ]] || return 1
  printf '%s\n' "$remote"
}

github_api_get() {
  local url="$1"
  local -a curl_args=(-fsSL -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28')
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    curl_args+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
  fi
  curl "${curl_args[@]}" "$url"
}

json_get_field() {
  local key="$1"
  node -e '
const fs = require("fs");
const key = process.argv[1];
const obj = JSON.parse(fs.readFileSync(0, "utf8"));
const value = obj[key];
if (value === undefined || value === null) process.exit(1);
if (typeof value === "object") {
  process.stdout.write(JSON.stringify(value));
} else {
  process.stdout.write(String(value));
}
' "$key"
}

json_find_asset_url() {
  local asset_name="$1"
  node -e '
const fs = require("fs");
const wanted = process.argv[1];
const obj = JSON.parse(fs.readFileSync(0, "utf8"));
const asset = (obj.assets || []).find(a => a.name === wanted);
if (!asset || !asset.browser_download_url) process.exit(1);
process.stdout.write(asset.browser_download_url);
' "$asset_name"
}

download_release_bundle() {
  local slug="${BOUW_RELEASE_REPO_SLUG:-}"
  local release_url="${BOUW_RELEASE_API_URL:-}"
  local release_json manifest_url manifest_json bundle_url sha_url source_sha bundle_tmp bundle_dir actual_sha expected_sha
  if [[ -z "$slug" ]]; then
    slug="$(repo_slug_from_origin)" || return 1
  fi
  if [[ -z "$release_url" ]]; then
    release_url="https://api.github.com/repos/${slug}/releases/latest"
  fi

  release_json="$(github_api_get "$release_url")" || return 1
  manifest_url="$(printf '%s' "$release_json" | json_find_asset_url "bouw-manifest.json")" || return 1
  manifest_json="$(github_api_get "$manifest_url")" || return 1
  source_sha="$(printf '%s' "$manifest_json" | json_get_field "source_sha")" || return 1
  bundle_url="$(printf '%s' "$release_json" | json_find_asset_url "bouw-runtime.tar.gz")" || return 1
  sha_url="$(printf '%s' "$release_json" | json_find_asset_url "bouw-runtime.tar.gz.sha256" 2>/dev/null || true)"

  bundle_tmp="$(mktemp -d)"
  bundle_dir="$bundle_tmp/bundle"
  mkdir -p "$bundle_dir"
  curl -fsSL "$bundle_url" -o "$bundle_tmp/bouw-runtime.tar.gz"
  if [[ -n "$sha_url" ]]; then
    expected_sha="$(github_api_get "$sha_url" | awk '{print $1}')"
    if command -v sha256sum >/dev/null 2>&1; then
      actual_sha="$(sha256sum "$bundle_tmp/bouw-runtime.tar.gz" | awk '{print $1}')"
    else
      actual_sha="$(shasum -a 256 "$bundle_tmp/bouw-runtime.tar.gz" | awk '{print $1}')"
    fi
    if [[ "$expected_sha" != "$actual_sha" ]]; then
      rm -rf "$bundle_tmp"
      die "Release bundle checksum mismatch."
    fi
  fi
  tar -xzf "$bundle_tmp/bouw-runtime.tar.gz" -C "$bundle_dir"

  RELEASE_BUNDLE_SOURCE_SHA="$source_sha"
  RELEASE_BUNDLE_TMP="$bundle_tmp"
  RELEASE_BUNDLE_DIR="$bundle_dir"
  RELEASE_BUNDLE_VERSION="$(printf '%s' "$manifest_json" | json_get_field "release_name" 2>/dev/null || true)"
  return 0
}

install_release_bundle() {
  local bundle_dir="$1"
  cp "$bundle_dir"/bouw-server.jar "$BOUW_HOME/bin/bouw-server.jar"
  if [[ -f "$bundle_dir/agent-discord.jar" ]]; then
    cp "$bundle_dir/agent-discord.jar" "$BOUW_HOME/bin/agent-discord.jar"
  fi
}

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

# Set to true if we add the install user to the docker group during this run; used to
# remind the user that interactive `docker` use needs a fresh login to pick up the group.
DOCKER_GROUP_PENDING=false

if [[ -n "${BOUW_LAUNCHER_PATH:-}" ]]; then
  LAUNCHER_PATH="${BOUW_LAUNCHER_PATH}"
else
  existing_bouw="$(command -v bouw 2>/dev/null || true)"
  if [[ -n "$existing_bouw" && "$existing_bouw" != "$REPO_DIR/bouw/bin/bouw.js" && "$existing_bouw" != "$REPO_DIR/install.sh" ]]; then
    LAUNCHER_PATH="$existing_bouw"
  elif [[ "$OS_TYPE" == "macos" ]]; then
    LAUNCHER_PATH="/opt/homebrew/bin/${LAUNCHER_NAME}"
  else
    LAUNCHER_PATH="/usr/local/bin/${LAUNCHER_NAME}"
  fi
fi

if [[ "$OS_TYPE" == "macos" ]]; then
  PLIST_LABEL="com.bouw.agent"
  PLIST_PATH="$HOME/Library/LaunchAgents/${PLIST_LABEL}.plist"

  pkg_update()          { brew update -q; }
  pkg_install()         { brew install "$@"; }
  pkg_install_java()    {
    brew install --cask temurin@21 2>/dev/null \
      || brew install temurin@21 2>/dev/null \
      || die "Could not install Temurin 21 via Homebrew. Install manually from https://adoptium.net"
  }
  pkg_install_redis()   { brew install redis; }

  pkg_install_docker() {
    # Project (GitHub repository) chats run in isolated Docker containers, so the Docker
    # CLI + daemon are required. On macOS that means Docker Desktop.
    if resolve_docker_bin >/dev/null 2>&1; then
      info "Docker already present."
    else
      warn "Docker not found — installing Docker Desktop via Homebrew..."
      brew install --cask docker 2>/dev/null \
        || die "Could not install Docker Desktop via Homebrew. Install it from https://www.docker.com/products/docker-desktop/ and re-run."
    fi
  }
  docker_daemon_ok() {
    local docker_bin
    docker_bin="$(resolve_docker_bin)" || return 1
    "$docker_bin" info >/dev/null 2>&1
  }
  docker_start_daemon() {
    docker_daemon_ok && return 0
    info "Starting Docker Desktop (this can take up to a minute)..."
    open -a Docker >/dev/null 2>&1 || open -a "Docker Desktop" >/dev/null 2>&1 || return 1
    local waited=0
    until docker_daemon_ok; do
      waited=$((waited + 2))
      [[ $waited -ge 60 ]] && return 1
      sleep 2
    done
  }
  docker_build_sandbox_image() {
    local docker_bin
    docker_bin="$(resolve_docker_bin)" || return 1
    "$docker_bin" build -t "$1" "$2"
  }

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
    <key>BOUW_HOME</key>  <string>${BOUW_HOME}</string>
    <key>AGENT_HOME</key>  <string>${BOUW_HOME}</string>
  </dict>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>-c</string>
    <string>set -a; source ${ENV_FILE}; set +a; export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin; exec /usr/bin/java -jar ${BOUW_HOME}/bin/bouw-server.jar --spring.config.additional-location=file:${CONFIG_YML}</string>
  </array>
  <key>WorkingDirectory</key>  <string>${BOUW_HOME}</string>
  <key>StandardOutPath</key>   <string>${BOUW_HOME}/logs/bouw.log</string>
  <key>StandardErrorPath</key> <string>${BOUW_HOME}/logs/bouw.log</string>
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
  svc_logs()      { exec tail -f "$BOUW_HOME/logs/bouw.log"; }
  svc_is_active() { launchctl list 2>/dev/null | grep -q "$PLIST_LABEL"; }
  svc_is_enabled(){ [[ -f "$PLIST_PATH" ]]; }
  svc_enable()    { launchctl load -w "$PLIST_PATH" 2>/dev/null || true; }
  svc_daemon_reload() { :; }  # no-op on macOS

  UPDATE_PLIST_LABEL="com.bouw.autoupdate"
  UPDATE_PLIST_PATH="$HOME/Library/LaunchAgents/${UPDATE_PLIST_LABEL}.plist"

  update_svc_install() {
    mkdir -p "$HOME/Library/LaunchAgents"
    cat > "$UPDATE_PLIST_PATH" <<UPDATE_PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>             <string>${UPDATE_PLIST_LABEL}</string>
  <key>UserName</key>          <string>${INSTALL_USER}</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>BOUW_HOME</key>  <string>${BOUW_HOME}</string>
  </dict>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>-c</string>
    <string>set -a; source ${ENV_FILE}; set +a; export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin; exec ${AUTO_UPDATE_SCRIPT}</string>
  </array>
  <key>WorkingDirectory</key>  <string>${BOUW_HOME}</string>
  <key>StandardOutPath</key>   <string>${BOUW_HOME}/logs/bouw-update.log</string>
  <key>StandardErrorPath</key> <string>${BOUW_HOME}/logs/bouw-update.log</string>
  <key>StartInterval</key>     <integer>${AUTO_UPDATE_INTERVAL_SECS}</integer>
</dict>
</plist>
UPDATE_PLIST
    launchctl unload "$UPDATE_PLIST_PATH" 2>/dev/null || true
    launchctl load -w "$UPDATE_PLIST_PATH"
  }

  update_svc_uninstall() {
    launchctl unload "$UPDATE_PLIST_PATH" 2>/dev/null || true
    rm -f "$UPDATE_PLIST_PATH"
  }

  DISCORD_PLIST_LABEL="com.bouw.discord"
  DISCORD_PLIST_PATH="$HOME/Library/LaunchAgents/${DISCORD_PLIST_LABEL}.plist"

  discord_has_token() { grep -qE '^DISCORD_BOT_TOKEN=.+' "${ENV_FILE:-$BOUW_HOME/bouw.env}" 2>/dev/null; }

  discord_svc_install() {
    if ! discord_has_token; then
      info "DISCORD_BOT_TOKEN not set in $ENV_FILE — skipping Discord bot service."
      return
    fi
    if [[ ! -f "$BOUW_HOME/bin/agent-discord.jar" ]]; then
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
    <key>BOUW_HOME</key>  <string>${BOUW_HOME}</string>
  </dict>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>-c</string>
    <string>set -a; source ${ENV_FILE}; set +a; export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin; exec /usr/bin/java -jar ${BOUW_HOME}/bin/agent-discord.jar</string>
  </array>
  <key>WorkingDirectory</key>  <string>${BOUW_HOME}</string>
  <key>StandardOutPath</key>   <string>${BOUW_HOME}/logs/discord.log</string>
  <key>StandardErrorPath</key> <string>${BOUW_HOME}/logs/discord.log</string>
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
  DISCORD_SERVICE_FILE="/etc/systemd/system/bouw-discord.service"

  discord_has_token() { grep -qE '^DISCORD_BOT_TOKEN=.+' "${ENV_FILE:-$BOUW_HOME/bouw.env}" 2>/dev/null; }

  discord_svc_install() {
    if ! discord_has_token; then
      info "DISCORD_BOT_TOKEN not set in $ENV_FILE — skipping Discord bot service."
      return
    fi
    if [[ ! -f "$BOUW_HOME/bin/agent-discord.jar" ]]; then
      info "agent-discord.jar not found — skipping Discord bot service."
      return
    fi
    sudo_retry --reason "write Discord systemd service file" tee "$DISCORD_SERVICE_FILE" > /dev/null <<DISCORD_SERVICE
# Bouw Discord bot service — managed by install.sh
[Unit]
Description=Bouw Discord Bot
After=network-online.target bouw.service
Wants=network-online.target

[Service]
Type=simple
User=${INSTALL_USER}
Environment=BOUW_HOME=${BOUW_HOME}
EnvironmentFile=${ENV_FILE}
WorkingDirectory=${BOUW_HOME}
ExecStart=/usr/bin/java -jar ${BOUW_HOME}/bin/agent-discord.jar
StandardOutput=append:${BOUW_HOME}/logs/discord.log
StandardError=append:${BOUW_HOME}/logs/discord.log
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
DISCORD_SERVICE
    sudo_retry --reason "reload systemd for Discord service" systemctl daemon-reload
    sudo systemctl enable bouw-discord
    success "Discord bot service installed."
  }

  discord_svc_uninstall() {
    sudo systemctl disable --now bouw-discord 2>/dev/null || true
    sudo rm -f "$DISCORD_SERVICE_FILE"
    sudo systemctl daemon-reload
  }

  discord_svc_start()     { discord_has_token && sudo systemctl start bouw-discord 2>/dev/null || true; }
  discord_svc_stop()      { sudo systemctl stop bouw-discord 2>/dev/null || true; }
  discord_svc_restart()   { sudo systemctl restart bouw-discord 2>/dev/null || true; }
  discord_svc_is_active() { systemctl is-active --quiet bouw-discord 2>/dev/null; }

  pkg_install_redis()   { sudo apt-get install -y redis-server; }

  pkg_install_docker() {
    # Project (GitHub repository) chats run in isolated Docker containers, so the Docker
    # CLI + daemon are required. docker.io provides both from the distro repositories.
    if require_cmd docker; then
      info "Docker already present."
    else
      warn "Docker not found — installing Docker Engine (docker.io)..."
      pkg_update
      sudo apt-get install -y docker.io
    fi
    # Run the daemon now and on boot; project-chat sandboxes need it.
    sudo systemctl enable --now docker 2>/dev/null || true
    # Let the install user (and the bouw systemd service) reach the daemon without sudo.
    # systemd re-reads supplementary groups when the service (re)starts later in this script.
    if ! id -nG "$INSTALL_USER" 2>/dev/null | tr ' ' '\n' | grep -qx docker; then
      sudo usermod -aG docker "$INSTALL_USER" 2>/dev/null || true
      DOCKER_GROUP_PENDING=true
    fi
  }
  docker_daemon_ok() { sudo docker info >/dev/null 2>&1; }
  docker_start_daemon() {
    sudo systemctl enable --now docker 2>/dev/null || true
    docker_daemon_ok
  }
  # During install the current shell is not yet in the docker group, so build via sudo; the
  # image lives in the shared daemon and is visible to the bouw service user once it restarts.
  docker_build_sandbox_image() { sudo docker build -t "$1" "$2"; }

  svc_install() {
    sudo_retry --reason "write systemd service file to /etc/systemd/system/" tee "$SERVICE_FILE" > /dev/null <<SERVICE
# Bouw agent service — managed by install.sh
[Unit]
Description=Bouw Agent Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${INSTALL_USER}
Environment=BOUW_HOME=${BOUW_HOME}
Environment=AGENT_HOME=${BOUW_HOME}
EnvironmentFile=${ENV_FILE}
Environment=PATH=/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin
WorkingDirectory=${BOUW_HOME}
ExecStart=/usr/bin/java -jar ${BOUW_HOME}/bin/bouw-server.jar \
  --spring.config.additional-location=file:${BOUW_HOME}/config/application.yml
StandardOutput=append:${BOUW_HOME}/logs/bouw.log
StandardError=append:${BOUW_HOME}/logs/bouw.log
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

  UPDATE_SERVICE_FILE="/etc/systemd/system/bouw-autoupdate.service"
  UPDATE_TIMER_FILE="/etc/systemd/system/bouw-autoupdate.timer"

  update_svc_install() {
    sudo_retry --reason "write autoupdate systemd service file" tee "$UPDATE_SERVICE_FILE" > /dev/null <<UPDATE_SERVICE
# Bouw auto-update service — managed by install.sh
[Unit]
Description=Bouw Auto Update
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=${INSTALL_USER}
Environment=BOUW_HOME=${BOUW_HOME}
Environment=PATH=/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin
WorkingDirectory=${BOUW_HOME}
ExecStart=${AUTO_UPDATE_SCRIPT}
StandardOutput=append:${BOUW_HOME}/logs/bouw-update.log
StandardError=append:${BOUW_HOME}/logs/bouw-update.log
UPDATE_SERVICE
    sudo_retry --reason "write autoupdate systemd timer file" tee "$UPDATE_TIMER_FILE" > /dev/null <<UPDATE_TIMER
# Bouw auto-update timer — managed by install.sh
[Unit]
Description=Run Bouw auto-update periodically

[Timer]
OnBootSec=2min
OnUnitActiveSec=${AUTO_UPDATE_INTERVAL_SECS}s
Persistent=true
Unit=bouw-autoupdate.service

[Install]
WantedBy=timers.target
UPDATE_TIMER
    sudo_retry --reason "reload systemd for autoupdate service" systemctl daemon-reload
    sudo systemctl enable --now bouw-autoupdate.timer
  }

  update_svc_uninstall() {
    sudo systemctl disable --now bouw-autoupdate.timer 2>/dev/null || true
    sudo rm -f "$UPDATE_SERVICE_FILE" "$UPDATE_TIMER_FILE"
    sudo systemctl daemon-reload
  }

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
      warn "Inspect logs:  bouw logs"
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
  update_svc_uninstall
  success "Service and launcher removed."

  if [[ -d "$BOUW_HOME" ]]; then
    ask "Delete $BOUW_HOME (config + workspace + logs)? [y/N] "; read -r _confirm; echo
    if [[ "$(echo "$_confirm" | tr '[:upper:]' '[:lower:]')" == "y" ]]; then
      rm -rf "$BOUW_HOME"
      success "$BOUW_HOME deleted."
    else
      info "Kept $BOUW_HOME."
    fi
  fi
  exit 0
fi

# ── detect existing install ───────────────────────────────────────────────────
ALREADY_INSTALLED=false
SKIP_BUILD=false
SKIP_CREDENTIALS=false

if [[ -f "$BOUW_HOME/bin/bouw-server.jar" ]]; then
  ALREADY_INSTALLED=true
  if [[ "$_force_reinstall" == "true" ]]; then
    SKIP_CREDENTIALS=true
    info "Reinstall mode — jars will be rebuilt using existing credentials."
  else
    warn "Existing Bouw installation detected at $BOUW_HOME."
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
info "Bouw home : $BOUW_HOME"
info "Repo root  : $REPO_DIR"
echo

# ── 1. directory tree ─────────────────────────────────────────────────────────
mkdir -p \
  "$BOUW_HOME/bin" \
  "$BOUW_HOME/config" \
  "$BOUW_HOME/workspace" \
  "$BOUW_HOME/logs"
info "Directory tree ready at $BOUW_HOME"

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
  require_cmd curl    || pkg_install curl
  require_cmd node    || pkg_install node
  pkg_install_docker

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
  require_cmd curl    || pkgs_to_install+=(curl)
  require_cmd node    || pkgs_to_install+=(nodejs)

  if [[ ${#pkgs_to_install[@]} -gt 0 ]]; then
    info "Installing system packages: ${pkgs_to_install[*]}"
    pkg_update
    pkg_install "${pkgs_to_install[@]}"
  fi

  pkg_install_docker
fi

# ── 3. prompts ────────────────────────────────────────────────────────────────

# Incrementally persist collected values so a mid-run failure doesn't lose them.
save_env() {
  mkdir -p "$BOUW_HOME/db"
  local current_bouw_version="${BOUW_VERSION:-$(resolve_bouw_version)}"
  local current_bouw_source_sha="${BOUW_UPDATE_SOURCE_SHA:-$(resolve_bouw_source_sha)}"
  cat > "$ENV_FILE" <<EOF
# Bouw environment — sourced by the service and the bouw launcher.
# Permissions: 600 (owner-read-only).  Do not commit this file.

OPEN_ROUTER_API_KEY=${OPENROUTER_KEY:-}
LLM_MODEL=${LLM_MODEL:-}
LLM_REASONING_EFFORT=${LLM_REASONING_EFFORT:-medium}

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

# Google Workspace OAuth (optional)
GOOGLE_OAUTH_CLIENT_SECRETS_FILE=${GOOGLE_OAUTH_CLIENT_SECRETS_FILE:-}
GOOGLE_OAUTH_TOKEN_DIR=${GOOGLE_OAUTH_TOKEN_DIR:-}

# Bouw home directory (workspace root is $BOUW_HOME/workspace)
AGENT_HOME=${BOUW_HOME}
BOUW_HOME=${BOUW_HOME}
BOUW_SANDBOX_DOCKER_BIN=$(resolve_docker_bin || true)
SANDBOX_DOCKER_BIN=$(resolve_docker_bin || true)
BOUW_VERSION=${current_bouw_version}
BOUW_UPDATE_SOURCE_SHA=${current_bouw_source_sha}
BOUW_REPO_DIR=${REPO_DIR}
BOUW_LAUNCHER_PATH=${LAUNCHER_PATH}

# H2 database for user accounts (dashboard login)
DB_URL=jdbc:h2:file:${BOUW_HOME}/db/bouw

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
_existing_google_oauth_client_secrets_file="" _existing_google_oauth_token_dir=""
if [[ -f "$ENV_FILE" ]]; then
  _existing_key=$(grep -E '^OPEN_ROUTER_API_KEY=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_agent_key=$(grep -E '^AGENT_API_KEY=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_redis_host=$(grep -E '^REDIS_HOST=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_redis_port=$(grep -E '^REDIS_PORT=' "$ENV_FILE" | cut -d= -f2- || echo "6379")
  _existing_model=$(grep -E '^LLM_MODEL=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_github_token=$(grep -E '^GITHUB_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_discord_token=$(grep -E '^DISCORD_BOT_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_nr_token=$(grep -E '^NEW_RELIC_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_google_oauth_client_secrets_file=$(grep -E '^GOOGLE_OAUTH_CLIENT_SECRETS_FILE=' "$ENV_FILE" | cut -d= -f2- || true)
  _existing_google_oauth_token_dir=$(grep -E '^GOOGLE_OAUTH_TOKEN_DIR=' "$ENV_FILE" | cut -d= -f2- || true)
fi

# Initialise all config vars to empty so save_env can be called at any point.
OPENROUTER_KEY="" LLM_MODEL="" AGENT_API_KEY="" MEMORY_ENABLED=false
REDIS_HOST_VAL="" REDIS_PORT_VAL=6379 GITHUB_TOKEN="" CLOUD_AGENTS_ENABLED=false
ADMIN_USERNAME="" ADMIN_PASSWORD="" JWT_SECRET="" DISCORD_BOT_TOKEN=""
NEW_RELIC_TOKEN="" NEW_RELIC_ENABLED=false
GOOGLE_OAUTH_CLIENT_SECRETS_FILE="" GOOGLE_OAUTH_TOKEN_DIR=""

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
    JWT_SECRET=$(openssl rand -base64 48 2>/dev/null | tr -d '\n')
    info "JWT secret auto-generated."
  fi
  # Prefer existing env file value, fall back to shell env var (handles first-run after manual token setup)
  DISCORD_BOT_TOKEN="${_existing_discord_token:-${DISCORD_BOT_TOKEN:-}}"
  NEW_RELIC_TOKEN="${_existing_nr_token:-}"
  NEW_RELIC_ENABLED=$(grep -E '^NEW_RELIC_ENABLED=' "$ENV_FILE" 2>/dev/null | cut -d= -f2- || echo "false")
  GOOGLE_OAUTH_CLIENT_SECRETS_FILE="${_existing_google_oauth_client_secrets_file:-${GOOGLE_OAUTH_CLIENT_SECRETS_FILE:-}}"
  GOOGLE_OAUTH_TOKEN_DIR="${_existing_google_oauth_token_dir:-${GOOGLE_OAUTH_TOKEN_DIR:-}}"
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
info "These credentials are used to log in to the Bouw web dashboard."

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
    JWT_SECRET=$(openssl rand -base64 48 2>/dev/null | tr -d '\n')
    success "JWT secret auto-generated (${#JWT_SECRET} chars)."
    ;;
esac
fi  # end SKIP_CREDENTIALS check

# ── 4. write bouw.env ────────────────────────────────────────────────────────
save_env
success "Wrote $ENV_FILE (chmod 600)"

# ── 5. write config/application.yml ──────────────────────────────────────────
cat > "$CONFIG_YML" <<EOF
# Per-installation overrides — merged on top of the bundled application.yml.
llm:
  model: \${LLM_MODEL:${LLM_MODEL}}

agent:
  api-key: \${AGENT_API_KEY:}
  home: ${BOUW_HOME}
  tools:
    workspace-root: ${BOUW_HOME}/workspace
  cloud:
    enabled: \${CLOUD_AGENTS_ENABLED:false}
    github-token: \${GITHUB_TOKEN:}
    cleanup-on-complete: true

logging:
  file:
    name: ${BOUW_HOME}/logs/bouw.log

# Environment variables DB_URL, ADMIN_USERNAME, ADMIN_PASSWORD, JWT_SECRET
# are sourced from bouw.env and picked up by application.yml property placeholders.
EOF
info "Wrote $CONFIG_YML"

# ── 6. build fat jars ────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" == "true" ]]; then
  info "Skipping Maven build (reconfigure-only mode)."
else
  info "Building fat jars — this may take a few minutes..."
  MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m}" \
    mvn -f "$REPO_DIR/pom.xml" \
        clean package -DskipTests -q
  cp "$REPO_DIR"/backend/target/bouw-backend-*.jar  "$BOUW_HOME/bin/bouw-server.jar"
  success "Jars built and copied to $BOUW_HOME/bin/"
fi

# ── 7. build the project-chat sandbox image ──────────────────────────────────
# Project (GitHub repository) chats run in an isolated Docker container built from
# docker/sandbox/Dockerfile. Without a running Docker daemon and this image, the dashboard
# reports "the Docker CLI is unavailable" when opening a project. Built regardless of
# SKIP_BUILD since it is independent of the Maven jar build.
BOUW_SANDBOX_IMAGE="${BOUW_SANDBOX_IMAGE:-bouw-agent-sandbox:latest}"
if require_cmd docker; then
  if docker_start_daemon; then
    info "Building project-chat sandbox image ($BOUW_SANDBOX_IMAGE) — this may take a minute..."
    if docker_build_sandbox_image "$BOUW_SANDBOX_IMAGE" "$REPO_DIR/docker/sandbox"; then
      success "Sandbox image ready: $BOUW_SANDBOX_IMAGE"
    else
      warn "Failed to build the sandbox image. Project chats will not work until it is built:"
      warn "  docker build -t $BOUW_SANDBOX_IMAGE $REPO_DIR/docker/sandbox"
    fi
  else
    warn "Docker is installed but the daemon is not reachable — skipping sandbox image build."
    if [[ "$OS_TYPE" == "macos" ]]; then
      warn "Start Docker Desktop, then run:"
    else
      warn "Start it with 'sudo systemctl enable --now docker', then run:"
    fi
    warn "  docker build -t $BOUW_SANDBOX_IMAGE $REPO_DIR/docker/sandbox"
  fi
else
  warn "Docker CLI not found — project (GitHub repository) chats require Docker."
  warn "Install Docker, then build the sandbox image:"
  warn "  docker build -t $BOUW_SANDBOX_IMAGE $REPO_DIR/docker/sandbox"
fi

# ── 8. install the bouw launcher ────────────────────────────────────────────
info "Installing $LAUNCHER_NAME launcher to $LAUNCHER_PATH..."
BOUW_VERSION="$(resolve_bouw_version)"
_launcher_tmp=$(mktemp)
cat > "$_launcher_tmp" <<'LAUNCHER_EOF'
#!/usr/bin/env bash
# bouw — Bouw agent launcher (installed by install.sh)
#
# Commands:
#   bouw [run]    start service if needed
#   bouw serve    run server in the foreground (no service manager)
#   bouw start / stop / restart / status / logs
#   bouw version  print the installed Bouw version
#   bouw config   re-prompt for credentials, restart service
#   bouw doctor   health-check every subsystem; auto-fix what it can
#   bouw uninstall
set -euo pipefail

BOUW_HOME="${BOUW_HOME:-__BOUW_HOME__}"
ENV_FILE="$BOUW_HOME/bouw.env"
CONFIG_YML="$BOUW_HOME/config/application.yml"
SERVICE_NAME="__SERVICE_NAME__"
LAUNCHER_PATH="__LAUNCHER_PATH__"
REPO_DIR="__REPO_DIR__"
BOUW_VERSION="__BOUW_VERSION__"

info()    { printf '\033[1;34m[bouw]\033[0m %s\n' "$*"; }
success() { printf '\033[1;32m[bouw]\033[0m %s\n' "$*"; }
warn()    { printf '\033[1;33m[bouw]\033[0m %s\n' "$*"; }
die()     { printf '\033[1;31m[bouw]\033[0m %s\n' "$*" >&2; exit 1; }

# ── OS detection + service helpers ───────────────────────────────────────────
OS_TYPE="linux"
if [[ "$(uname -s)" == "Darwin" ]]; then
  OS_TYPE="macos"
fi

if [[ "$OS_TYPE" == "macos" ]]; then
  PLIST_LABEL="com.bouw.agent"
  PLIST_PATH="$HOME/Library/LaunchAgents/${PLIST_LABEL}.plist"

  svc_start()     { launchctl start "$PLIST_LABEL"; }
  svc_stop()      { launchctl stop  "$PLIST_LABEL" 2>/dev/null || true; }
  svc_restart()   { svc_stop; sleep 1; svc_start; }
  svc_status()    { launchctl list "$PLIST_LABEL" 2>/dev/null || echo "not loaded"; }
  svc_logs()      { exec tail -f "$BOUW_HOME/logs/bouw.log"; }
  svc_is_active() { launchctl list 2>/dev/null | grep -q "$PLIST_LABEL"; }
  svc_is_enabled(){ [[ -f "$PLIST_PATH" ]]; }
  svc_enable()    { launchctl load -w "$PLIST_PATH" 2>/dev/null || true; }

  svc_uninstall() {
    launchctl unload "$PLIST_PATH" 2>/dev/null || true
    rm -f "$PLIST_PATH"
  }

  DISCORD_PLIST_LABEL="com.bouw.discord"
  DISCORD_PLIST_PATH="$HOME/Library/LaunchAgents/${DISCORD_PLIST_LABEL}.plist"

  discord_has_token() { grep -qE '^DISCORD_BOT_TOKEN=.+' "${ENV_FILE:-$BOUW_HOME/bouw.env}" 2>/dev/null; }
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

  discord_has_token() { grep -qE '^DISCORD_BOT_TOKEN=.+' "${ENV_FILE:-$BOUW_HOME/bouw.env}" 2>/dev/null; }
  discord_svc_is_active() { systemctl is-active --quiet bouw-discord 2>/dev/null; }
  discord_svc_stop()      { sudo systemctl stop bouw-discord 2>/dev/null || true; }
  discord_svc_start()     { discord_has_token && sudo systemctl start bouw-discord 2>/dev/null || true; }
  discord_svc_restart()   { sudo systemctl restart bouw-discord 2>/dev/null || true; }

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
    [[ $elapsed -ge $max ]] && { warn "Server did not respond within ${max}s. Try: bouw logs"; return 1; }
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
}

cmd_serve() {
  load_env
  exec java -jar "$BOUW_HOME/bin/bouw-server.jar" \
    "--spring.config.additional-location=file:${CONFIG_YML}"
}

cmd_start()   { svc_start; }
cmd_stop()    { svc_stop; }
cmd_restart() { svc_restart; }
cmd_status()  { svc_status; }
cmd_logs()    { svc_logs; }
resolve_installed_version() {
  local package_json="$REPO_DIR/package.json"
  local version=""
  if [[ -f "$package_json" ]]; then
    version=$(grep -m1 '"version"' "$package_json" | sed -E 's/.*"version"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/' || true)
  fi
  [[ -n "$version" ]] && echo "$version" || echo "$BOUW_VERSION"
}
cmd_version() { resolve_installed_version; }

repo_slug_from_origin() {
  local remote
  remote="$(git -C "$REPO_DIR" remote get-url origin 2>/dev/null || true)"
  case "$remote" in
    git@github.com:*.git)
      remote="${remote#git@github.com:}"
      remote="${remote%.git}"
      ;;
    git@github.com:*)
      remote="${remote#git@github.com:}"
      ;;
    https://github.com/*.git)
      remote="${remote#https://github.com/}"
      remote="${remote%.git}"
      ;;
    https://github.com/*)
      remote="${remote#https://github.com/}"
      ;;
    *)
      return 1
      ;;
  esac
  [[ -n "$remote" ]] || return 1
  printf '%s\n' "$remote"
}

github_api_get() {
  local url="$1"
  local -a curl_args=(-fsSL -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28')
  if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    curl_args+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
  fi
  curl "${curl_args[@]}" "$url"
}

json_get_field() {
  local key="$1"
  node -e '
const fs = require("fs");
const key = process.argv[1];
const obj = JSON.parse(fs.readFileSync(0, "utf8"));
const value = obj[key];
if (value === undefined || value === null) process.exit(1);
if (typeof value === "object") {
  process.stdout.write(JSON.stringify(value));
} else {
  process.stdout.write(String(value));
}
' "$key"
}

json_find_asset_url() {
  local asset_name="$1"
  node -e '
const fs = require("fs");
const wanted = process.argv[1];
const obj = JSON.parse(fs.readFileSync(0, "utf8"));
const asset = (obj.assets || []).find(a => a.name === wanted);
if (!asset || !asset.browser_download_url) process.exit(1);
process.stdout.write(asset.browser_download_url);
' "$asset_name"
}

download_release_bundle() {
  local slug="${BOUW_RELEASE_REPO_SLUG:-}"
  local release_url="${BOUW_RELEASE_API_URL:-}"
  local release_json manifest_url manifest_json bundle_url sha_url source_sha bundle_tmp bundle_dir actual_sha expected_sha
  if [[ -z "$slug" ]]; then
    slug="$(repo_slug_from_origin)" || return 1
  fi
  if [[ -z "$release_url" ]]; then
    release_url="https://api.github.com/repos/${slug}/releases/latest"
  fi

  release_json="$(github_api_get "$release_url")" || return 1
  manifest_url="$(printf '%s' "$release_json" | json_find_asset_url "bouw-manifest.json")" || return 1
  manifest_json="$(github_api_get "$manifest_url")" || return 1
  source_sha="$(printf '%s' "$manifest_json" | json_get_field "source_sha")" || return 1
  bundle_url="$(printf '%s' "$release_json" | json_find_asset_url "bouw-runtime.tar.gz")" || return 1
  sha_url="$(printf '%s' "$release_json" | json_find_asset_url "bouw-runtime.tar.gz.sha256" 2>/dev/null || true)"

  bundle_tmp="$(mktemp -d)"
  bundle_dir="$bundle_tmp/bundle"
  mkdir -p "$bundle_dir"
  curl -fsSL "$bundle_url" -o "$bundle_tmp/bouw-runtime.tar.gz"
  if [[ -n "$sha_url" ]]; then
    expected_sha="$(github_api_get "$sha_url" | awk '{print $1}')"
    if command -v sha256sum >/dev/null 2>&1; then
      actual_sha="$(sha256sum "$bundle_tmp/bouw-runtime.tar.gz" | awk '{print $1}')"
    else
      actual_sha="$(shasum -a 256 "$bundle_tmp/bouw-runtime.tar.gz" | awk '{print $1}')"
    fi
    if [[ "$expected_sha" != "$actual_sha" ]]; then
      rm -rf "$bundle_tmp"
      die "Release bundle checksum mismatch."
    fi
  fi
  tar -xzf "$bundle_tmp/bouw-runtime.tar.gz" -C "$bundle_dir"

  RELEASE_BUNDLE_SOURCE_SHA="$source_sha"
  RELEASE_BUNDLE_TMP="$bundle_tmp"
  RELEASE_BUNDLE_DIR="$bundle_dir"
  RELEASE_BUNDLE_VERSION="$(printf '%s' "$manifest_json" | json_get_field "release_name" 2>/dev/null || true)"
  return 0
}

install_release_bundle() {
  local bundle_dir="$1"
  cp "$bundle_dir"/bouw-server.jar "$BOUW_HOME/bin/bouw-server.jar"
  if [[ -f "$bundle_dir/agent-discord.jar" ]]; then
    cp "$bundle_dir/agent-discord.jar" "$BOUW_HOME/bin/agent-discord.jar"
  fi
}

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
  local existing_google_oauth_client_secrets_file=""
  local existing_google_oauth_token_dir=""
  if [[ -f "$ENV_FILE" ]]; then
    existing_google_oauth_client_secrets_file=$(grep -E '^GOOGLE_OAUTH_CLIENT_SECRETS_FILE=' "$ENV_FILE" | cut -d= -f2- || true)
    existing_google_oauth_token_dir=$(grep -E '^GOOGLE_OAUTH_TOKEN_DIR=' "$ENV_FILE" | cut -d= -f2- || true)
  fi
  cat > "$ENV_FILE" <<ENV
OPEN_ROUTER_API_KEY=${new_key}
LLM_MODEL=${LLM_MODEL:-openai/gpt-oss-120b}
LLM_REASONING_EFFORT=${LLM_REASONING_EFFORT:-medium}
AGENT_API_KEY=${AGENT_API_KEY:-}
MEMORY_ENABLED=${mem}
REDIS_HOST=${rhost}
REDIS_PORT=${rport}
CLOUD_AGENTS_ENABLED=${CLOUD_AGENTS_ENABLED:-false}
GITHUB_TOKEN=${GITHUB_TOKEN:-}
NEW_RELIC_TOKEN=${NEW_RELIC_TOKEN:-}
NEW_RELIC_ENABLED=${NEW_RELIC_ENABLED:-false}
GOOGLE_OAUTH_CLIENT_SECRETS_FILE=${GOOGLE_OAUTH_CLIENT_SECRETS_FILE:-${existing_google_oauth_client_secrets_file:-}}
GOOGLE_OAUTH_TOKEN_DIR=${GOOGLE_OAUTH_TOKEN_DIR:-${existing_google_oauth_token_dir:-}}
AGENT_HOME=${BOUW_HOME}
DB_URL=jdbc:h2:file:${BOUW_HOME}/db/bouw
ADMIN_USERNAME=${ADMIN_USERNAME:-admin}
ADMIN_PASSWORD=${ADMIN_PASSWORD:-}
JWT_SECRET=${JWT_SECRET:-}
ENV
  chmod 600 "$ENV_FILE"
  success "bouw.env updated."
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

  printf '\033[1;34m─── Bouw Doctor ───────────────────────────────────────────────────────\033[0m\n'
  echo

  # ── Runtime ──────────────────────────────────────────────────────────────────
  printf '\033[1;34m[Runtime]\033[0m\n'

  if command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'; then
    local _jv; _jv=$(java -version 2>&1 | head -1)
    _dr_pass "Java 21+  ($_jv)"
  else
    _dr_fail "Java 21+ not found — required to run jars; re-run install.sh to install"
  fi

  echo
  # ── Files ────────────────────────────────────────────────────────────────────
  printf '\033[1;34m[Files]\033[0m\n'

  for _d in bin config workspace logs; do
    if [[ -d "$BOUW_HOME/$_d" ]]; then
      _dr_pass "~/.bouw/$_d/"
    else
      mkdir -p "$BOUW_HOME/$_d"
      _dr_fixed "Created missing directory ~/.bouw/$_d/"
    fi
  done

  if [[ -f "$ENV_FILE" ]]; then
    local _perms; _perms=$(stat -c '%a' "$ENV_FILE" 2>/dev/null \
                           || stat -f '%OLp' "$ENV_FILE" 2>/dev/null \
                           || echo "unknown")
    if [[ "$_perms" == "600" ]]; then
      _dr_pass "bouw.env (permissions 600)"
    else
      chmod 600 "$ENV_FILE"
      _dr_fixed "Fixed bouw.env permissions ($_perms → 600)"
    fi
    if grep -qE '^OPEN_ROUTER_API_KEY=.+' "$ENV_FILE" 2>/dev/null; then
      _dr_pass "OPEN_ROUTER_API_KEY is set"
    else
      _dr_fail "OPEN_ROUTER_API_KEY is missing or empty — run: bouw config"
    fi
  else
    _dr_fail "bouw.env not found at $ENV_FILE — run: bouw config  or re-run install.sh"
  fi

  if [[ -f "$CONFIG_YML" ]]; then
    _dr_pass "config/application.yml"
  else
    _dr_fail "config/application.yml not found — re-run install.sh"
  fi

  if [[ -f "$BOUW_HOME/bin/bouw-server.jar" ]]; then
    local _sz; _sz=$(du -sh "$BOUW_HOME/bin/bouw-server.jar" | cut -f1)
    _dr_pass "bouw-server.jar ($_sz)"
  else
    _dr_fail "bouw-server.jar not found — rebuild: mvn clean package -DskipTests && re-run install.sh"
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
        _dr_fail "Service started but exited — check: bouw logs"
      fi
    else
      _dr_fail "Could not start service — check: bouw logs"
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
      _dr_note "Tip: inspect with  bouw logs"
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
  # ── Sandbox (project chats) ───────────────────────────────────────────────────
  printf '\033[1;34m[Sandbox]\033[0m\n'

  # Prefer a direct docker call (works when in the docker group); fall back to non-interactive
  # sudo so the check never hangs waiting for a password.
  _docker() { docker "$@" 2>/dev/null || sudo -n docker "$@" 2>/dev/null; }
  _sandbox_image="${BOUW_SANDBOX_IMAGE:-bouw-agent-sandbox:latest}"
  if command -v docker >/dev/null 2>&1; then
    _dr_pass "Docker CLI present ($(docker --version 2>/dev/null | head -1))"
    if _docker info >/dev/null 2>&1; then
      _dr_pass "Docker daemon reachable"
      if _docker image inspect "$_sandbox_image" >/dev/null 2>&1; then
        _dr_pass "Sandbox image present ($_sandbox_image)"
      else
        _dr_fail "Sandbox image missing — build: docker build -t $_sandbox_image $REPO_DIR/docker/sandbox"
      fi
    elif [[ "$OS_TYPE" == "macos" ]]; then
      _dr_fail "Docker daemon not running — start Docker Desktop"
    else
      _dr_fail "Docker daemon not running — start: sudo systemctl enable --now docker"
    fi
  else
    _dr_fail "Docker CLI not found — project (GitHub repository) chats require Docker. Re-run install.sh"
  fi

  echo
  # ── Summary ──────────────────────────────────────────────────────────────────
  printf '\033[1;34m────────────────────────────────────────────────────────────────────────\033[0m\n'
  if [[ $_fails -eq 0 ]]; then
    if [[ $_fixes -gt 0 ]]; then
      success "All checks passed after $_fixes auto-fix(es). Bouw is healthy."
    else
      success "All checks passed. Bouw is healthy."
    fi
  else
    warn "$_fails check(s) failed, $_fixes item(s) auto-fixed."
    warn "Address the failures above, then re-run:  bouw doctor"
    return 1
  fi
}

cmd_update() {
  if [[ ! -d "$REPO_DIR/.git" ]]; then
    die "Repo not found at $REPO_DIR — cannot update. Re-run install.sh from the source directory."
  fi

  local checkout_branch
  checkout_branch="$(git -C "$REPO_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
  if [[ "$checkout_branch" == "main" ]]; then
    info "Pulling latest code from origin/main..."
    git -C "$REPO_DIR" pull --ff-only origin main || die "git pull failed — resolve conflicts manually and retry."
  else
    info "Repo checkout is on branch '$checkout_branch' — skipping git sync and updating the installed runtime from the current checkout."
  fi

  local current_head bundle_ready=false release_dir="" was_running=false
  current_head="$(git -C "$REPO_DIR" rev-parse HEAD)"

  if download_release_bundle; then
    if [[ "$RELEASE_BUNDLE_SOURCE_SHA" == "$current_head" ]]; then
      bundle_ready=true
      release_dir="$RELEASE_BUNDLE_DIR"
      info "Using latest GitHub release bundle (${RELEASE_BUNDLE_SOURCE_SHA:0:7})."
    else
      warn "Latest release bundle is for ${RELEASE_BUNDLE_SOURCE_SHA:0:7}, but repo is at ${current_head:0:7}. Falling back to local build."
      if [[ -n "${RELEASE_BUNDLE_TMP:-}" ]]; then
        rm -rf "$RELEASE_BUNDLE_TMP"
      fi
    fi
  else
    info "No compatible release bundle found — falling back to local build."
  fi

  if [[ "$bundle_ready" == "false" ]]; then
    info "Building jars (this may take a minute)..."
    MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m}" \
      mvn -f "$REPO_DIR/pom.xml" \
        clean package -DskipTests -q \
      || die "Maven build failed — check output above."
  fi

  if svc_is_active 2>/dev/null; then
    was_running=true

    info "Stopping service to swap jars..."
    svc_stop
    sleep 1
  fi

  if [[ "$bundle_ready" == "true" ]]; then
    install_release_bundle "$release_dir"
  else
    cp "$REPO_DIR"/backend/target/bouw-backend-*.jar  "$BOUW_HOME/bin/bouw-server.jar"
  fi

  local new_version
  new_version="$(resolve_installed_version)"
  local new_source_sha="$current_head"
  if [[ "$bundle_ready" == "true" && -n "${RELEASE_BUNDLE_SOURCE_SHA:-}" ]]; then
    new_source_sha="$RELEASE_BUNDLE_SOURCE_SHA"
  fi
  if [[ -f "$ENV_FILE" ]]; then
    if grep -q '^BOUW_VERSION=' "$ENV_FILE"; then
      sed -i.bak -E "s|^BOUW_VERSION=.*|BOUW_VERSION=${new_version}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
    else
      printf '\nBOUW_VERSION=%s\n' "$new_version" >> "$ENV_FILE"
    fi
    if grep -q '^BOUW_UPDATE_SOURCE_SHA=' "$ENV_FILE"; then
      sed -i.bak -E "s|^BOUW_UPDATE_SOURCE_SHA=.*|BOUW_UPDATE_SOURCE_SHA=${new_source_sha}|" "$ENV_FILE" && rm -f "$ENV_FILE.bak"
    else
      printf 'BOUW_UPDATE_SOURCE_SHA=%s\n' "$new_source_sha" >> "$ENV_FILE"
    fi
    if ! grep -q '^BOUW_REPO_DIR=' "$ENV_FILE"; then
      printf 'BOUW_REPO_DIR=%s\n' "$REPO_DIR" >> "$ENV_FILE"
    fi
    if ! grep -q '^BOUW_LAUNCHER_PATH=' "$ENV_FILE"; then
      printf 'BOUW_LAUNCHER_PATH=%s\n' "$LAUNCHER_PATH" >> "$ENV_FILE"
    fi
  fi
  success "Jars and scripts updated."

  if [[ "$was_running" == "true" ]]; then
    info "Restarting service..."
    svc_start
    local elapsed=0
    until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
      elapsed=$((elapsed + 1))
      [[ $elapsed -ge 60 ]] && { warn "Server did not respond within 60s. Try: bouw logs"; return 1; }
      sleep 1
    done
    success "Service restarted and healthy."
  else
    info "Service was not running — start it with: bouw start"
  fi

  if [[ -n "${RELEASE_BUNDLE_TMP:-}" ]]; then
    rm -rf "$RELEASE_BUNDLE_TMP"
  fi
}

cmd_uninstall() {
  info "Stopping and removing $SERVICE_NAME service..."
  svc_uninstall
  sudo_retry --reason "remove launcher from $LAUNCHER_PATH" rm -f "$LAUNCHER_PATH"
  success "Service and launcher removed."
  if [[ -d "$BOUW_HOME" ]]; then
    printf '\033[1;35m   >\033[0m Delete %s? [y/N] ' "$BOUW_HOME"
    read -r _c; echo
    if [[ "$(echo "$_c" | tr '[:upper:]' '[:lower:]')" == "y" ]]; then
      rm -rf "$BOUW_HOME"; success "$BOUW_HOME deleted."
    else
      info "Kept $BOUW_HOME."
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
  version|--version|-v) cmd_version ;;
  config)    cmd_config    ;;
  update)    cmd_update    ;;
  doctor)    cmd_doctor    ;;
  uninstall) cmd_uninstall ;;
  *)
    cat <<USAGE
Usage: bouw [command]

  (none) / run   Start service if needed
  serve          Run the server in the foreground (no service manager)
  start          Start the background service
  stop           Stop the background service
  restart        Restart the background service
  status         Show service status
  logs           Stream service logs
  version        Print the installed Bouw version
  config         Reconfigure credentials, restart service
  update         Pull latest code, rebuild jars, restart service
  doctor         Check every subsystem; auto-fix what it can
  uninstall      Remove service, launcher, and optionally ~/.bouw
USAGE
    exit 1
    ;;
esac
LAUNCHER_EOF

# Substitute the install-time paths into the temp file, then copy to final location
SED_INPLACE \
  -e "s|__BOUW_HOME__|${BOUW_HOME}|g" \
  -e "s|__SERVICE_NAME__|${SERVICE_NAME}|g" \
  -e "s|__LAUNCHER_PATH__|${LAUNCHER_PATH}|g" \
  -e "s|__REPO_DIR__|${REPO_DIR}|g" \
  -e "s|__BOUW_VERSION__|${BOUW_VERSION}|g" \
  "$_launcher_tmp"
if [[ -w "$LAUNCHER_PATH" || -w "$(dirname "$LAUNCHER_PATH")" ]]; then
  cp "$_launcher_tmp" "$LAUNCHER_PATH"
  chmod 0755 "$LAUNCHER_PATH"
else
  sudo_retry --reason "install launcher to $LAUNCHER_PATH" cp "$_launcher_tmp" "$LAUNCHER_PATH"
  sudo_retry --reason "make launcher executable" chmod 0755 "$LAUNCHER_PATH"
fi
rm -f "$_launcher_tmp"
success "Launcher installed: $LAUNCHER_PATH"

# ── 8b. install auto-update helper ────────────────────────────────────────────
info "Installing auto-update helper..."
_update_tmp=$(mktemp)
cat > "$_update_tmp" <<'UPDATE_EOF'
#!/usr/bin/env bash
# bouw-auto-update — pull from origin/main when new commits are available.
set -euo pipefail

BOUW_HOME="${BOUW_HOME:-__BOUW_HOME__}"
REPO_DIR="__REPO_DIR__"
LAUNCHER_PATH="__LAUNCHER_PATH__"

info() { printf '\033[1;34m[bouw-update]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[bouw-update]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[bouw-update]\033[0m %s\n' "$*" >&2; exit 1; }

if [[ ! -d "$REPO_DIR/.git" ]]; then
  die "Repo not found at $REPO_DIR."
fi

if [[ ! -x "$LAUNCHER_PATH" ]]; then
  die "Launcher not found at $LAUNCHER_PATH."
fi

info "Checking origin/main for new commits..."
git -C "$REPO_DIR" fetch origin main --prune

remote_head="$(git -C "$REPO_DIR" rev-parse origin/main)"
installed_head=""
if [[ -f "$BOUW_HOME/bouw.env" ]]; then
  installed_head="$(grep -E '^BOUW_UPDATE_SOURCE_SHA=' "$BOUW_HOME/bouw.env" 2>/dev/null | cut -d= -f2- || true)"
fi

if [[ -n "$installed_head" && "$installed_head" == "$remote_head" ]]; then
  info "Already up to date at ${remote_head:0:7}."
  exit 0
fi

tmp_worktree=""
cleanup() {
  if [[ -n "$tmp_worktree" && -d "$tmp_worktree" ]]; then
    git -C "$REPO_DIR" worktree remove --force "$tmp_worktree" >/dev/null 2>&1 || rm -rf "$tmp_worktree"
  fi
}
trap cleanup EXIT

tmp_worktree="$(mktemp -d "${TMPDIR:-/tmp}/bouw-update.XXXXXX")"
info "Checking out origin/main at ${remote_head:0:7} into a temporary worktree..."
git -C "$REPO_DIR" worktree add --detach "$tmp_worktree" "$remote_head" >/dev/null

info "Running installer from temporary main checkout..."
bash "$tmp_worktree/install.sh" --reinstall

info "Auto-update completed successfully."
UPDATE_EOF
SED_INPLACE \
  -e "s|__BOUW_HOME__|${BOUW_HOME}|g" \
  -e "s|__REPO_DIR__|${REPO_DIR}|g" \
  -e "s|__LAUNCHER_PATH__|${LAUNCHER_PATH}|g" \
  "$_update_tmp"
cp "$_update_tmp" "$AUTO_UPDATE_SCRIPT"
rm -f "$_update_tmp"
chmod 0755 "$AUTO_UPDATE_SCRIPT"
success "Auto-update helper installed: $AUTO_UPDATE_SCRIPT"

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

info "Installing auto-update timer/service..."
update_svc_install

# ── 9b. Discord bot service ───────────────────────────────────────────────────
info "Setting up Discord bot service..."
discord_svc_install

# ── 10. health check ──────────────────────────────────────────────────────────
wait_for_health 60

# ── done ──────────────────────────────────────────────────────────────────────
echo
printf '\033[1;32m══════════════════════════════════════════════════════════════════\033[0m\n'
printf '\033[1;32m  Bouw is running at http://localhost:8080\033[0m\n'
printf '\033[1;32m══════════════════════════════════════════════════════════════════\033[0m\n'
echo
cat <<MSG
  Dashboard:            http://localhost:8080
  Start chatting:       bouw
  Server in foreground: bouw serve
  Service status/logs:  bouw status  |  bouw logs
  Auto-update logs:     tail -f $BOUW_HOME/logs/bouw-update.log
  Health check:         bouw doctor
  Reconfigure:          bouw config
  Update (rebuild):     bouw update
  Workspace:            $BOUW_HOME/workspace
  Config:               $BOUW_HOME/config/
  Env vars:             $ENV_FILE
  Uninstall:            bouw uninstall
MSG
echo

if [[ "$DOCKER_GROUP_PENDING" == "true" ]]; then
  warn "Added $INSTALL_USER to the 'docker' group. The bouw service already picks this up,"
  warn "but to run 'docker' yourself without sudo, log out and back in (or run: newgrp docker)."
  echo
fi
