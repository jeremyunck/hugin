#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
ENV_DIR="$HOME/.config/bouw-dev"
ENV_FILE="$ENV_DIR/env"
LOG_DIR="$REPO_DIR/.data/logs"
DEV_HOME="${BOUW_DEV_HOME:-$HOME/.local/share/bouw-dev}"
DEPLOY_REPO_DIR="${BOUW_DEV_DEPLOY_REPO_DIR:-$DEV_HOME/repo}"
SERVICE_LABEL="${BOUW_DEV_SERVICE_LABEL:-com.jnku.bouw.repo-server}"
UPDATE_LABEL="${BOUW_DEV_UPDATE_LABEL:-com.jnku.bouw.repo-autoupdate}"
SERVICE_PLIST="$LAUNCH_AGENTS_DIR/${SERVICE_LABEL}.plist"
UPDATE_PLIST="$LAUNCH_AGENTS_DIR/${UPDATE_LABEL}.plist"
RUN_SCRIPT="$REPO_DIR/scripts/bouw-launchd-run.sh"
UPDATE_SCRIPT="$REPO_DIR/scripts/bouw-launchd-update.sh"
REPO_URL="$(git -C "$REPO_DIR" remote get-url origin)"

die() {
  printf '[bouw-launchd-install] %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1
}

resolve_docker_bin() {
  local candidate
  for candidate in \
    "${BOUW_SANDBOX_DOCKER_BIN:-}" \
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

DOCKER_BIN="$(resolve_docker_bin || true)"
if [[ -z "$DOCKER_BIN" ]]; then
  die "Docker CLI not found. Install Docker Desktop and re-run; project/GitHub chats require the sandbox image."
fi

if ! "$DOCKER_BIN" info >/dev/null 2>&1; then
  die "Docker daemon not reachable. Start Docker Desktop and re-run."
fi

mkdir -p "$LAUNCH_AGENTS_DIR" "$ENV_DIR" "$LOG_DIR" "$DEV_HOME" "$DEPLOY_REPO_DIR"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

quote_env() {
  printf '%q' "$1"
}

append_if_set() {
  local key="$1"
  local value="${2:-}"
  if [[ -n "$value" ]]; then
    printf '%s=%s\n' "$key" "$(quote_env "$value")" >> "$ENV_FILE"
  fi
}

: > "$ENV_FILE"
append_if_set OPEN_ROUTER_API_KEY "${OPEN_ROUTER_API_KEY:-}"
append_if_set SPRING_DATASOURCE_URL "${SPRING_DATASOURCE_URL:-}"
append_if_set SPRING_DATASOURCE_USERNAME "${SPRING_DATASOURCE_USERNAME:-}"
append_if_set SPRING_DATASOURCE_PASSWORD "${SPRING_DATASOURCE_PASSWORD:-}"
append_if_set AUTH_JWT_SECRET_BASE64 "${AUTH_JWT_SECRET_BASE64:-}"
append_if_set AUTH_BOOTSTRAP_PASSWORD "${AUTH_BOOTSTRAP_PASSWORD:-}"
append_if_set AUTH_TEST_USER_USERNAME "${AUTH_TEST_USER_USERNAME:-}"
append_if_set AUTH_TEST_USER_PASSWORD "${AUTH_TEST_USER_PASSWORD:-}"
append_if_set GOOGLE_OAUTH_CLIENT_SECRETS_FILE "${GOOGLE_OAUTH_CLIENT_SECRETS_FILE:-}"
append_if_set GOOGLE_OAUTH_TOKEN_DIR "${GOOGLE_OAUTH_TOKEN_DIR:-}"
append_if_set GOOGLE_APPLICATION_CREDENTIALS "${GOOGLE_APPLICATION_CREDENTIALS:-}"
append_if_set GOOGLE_IMPERSONATE_USER "${GOOGLE_IMPERSONATE_USER:-}"
append_if_set GOOGLE_DEFAULT_SHARE_WITH "${GOOGLE_DEFAULT_SHARE_WITH:-}"
append_if_set REDIS_HOST "${REDIS_HOST:-}"
append_if_set REDIS_PORT "${REDIS_PORT:-}"
append_if_set MANAGEMENT_HEALTH_REDIS_ENABLED "${MANAGEMENT_HEALTH_REDIS_ENABLED:-}"
append_if_set MEMORY_ENABLED "${MEMORY_ENABLED:-}"
append_if_set GITHUB_TOKEN "${GITHUB_TOKEN:-}"
append_if_set GITHUB_APP_ID "${GITHUB_APP_ID:-}"
append_if_set GITHUB_APP_SLUG "${GITHUB_APP_SLUG:-}"
append_if_set GITHUB_APP_PRIVATE_KEY_PATH "${GITHUB_APP_PRIVATE_KEY_PATH:-}"
append_if_set NEW_RELIC_ENABLED "${NEW_RELIC_ENABLED:-}"
append_if_set NEW_RELIC_TOKEN "${NEW_RELIC_TOKEN:-}"
append_if_set CLOUD_AGENTS_ENABLED "${CLOUD_AGENTS_ENABLED:-}"
append_if_set SCHEDULER_DEFAULT_ZONE "${SCHEDULER_DEFAULT_ZONE:-}"
append_if_set SCHEDULER_ENABLED "${SCHEDULER_ENABLED:-}"
append_if_set AGENT_HOME "$DEV_HOME"
append_if_set BOUW_DEV_HOME "$DEV_HOME"
append_if_set BOUW_DEV_ENV_FILE "$ENV_FILE"
append_if_set BOUW_DEV_LOG_DIR "$LOG_DIR"
append_if_set BOUW_DEV_SERVICE_LABEL "$SERVICE_LABEL"
append_if_set BOUW_DEV_UPDATE_LABEL "$UPDATE_LABEL"
append_if_set BOUW_DEV_DEPLOY_REPO_DIR "$DEPLOY_REPO_DIR"
append_if_set BOUW_DEV_REPO_URL "$REPO_URL"
append_if_set BOUW_SANDBOX_DOCKER_BIN "$DOCKER_BIN"
chmod 600 "$ENV_FILE"

cat > "$SERVICE_PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${SERVICE_LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>${RUN_SCRIPT}</string>
  </array>
  <key>WorkingDirectory</key>
  <string>${REPO_DIR}</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>BOUW_DEV_ENV_FILE</key>
    <string>${ENV_FILE}</string>
    <key>BOUW_DEV_LOG_DIR</key>
    <string>${LOG_DIR}</string>
    <key>BOUW_DEV_SERVICE_LABEL</key>
    <string>${SERVICE_LABEL}</string>
  </dict>
  <key>RunAtLoad</key>
  <true/>
  <key>KeepAlive</key>
  <true/>
  <key>StandardOutPath</key>
  <string>${LOG_DIR}/server.out.log</string>
  <key>StandardErrorPath</key>
  <string>${LOG_DIR}/server.err.log</string>
</dict>
</plist>
EOF

cat > "$UPDATE_PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>${UPDATE_LABEL}</string>
  <key>ProgramArguments</key>
  <array>
    <string>/bin/bash</string>
    <string>${UPDATE_SCRIPT}</string>
  </array>
  <key>WorkingDirectory</key>
  <string>${REPO_DIR}</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>BOUW_DEV_ENV_FILE</key>
    <string>${ENV_FILE}</string>
    <key>BOUW_DEV_LOG_DIR</key>
    <string>${LOG_DIR}</string>
    <key>BOUW_DEV_SERVICE_LABEL</key>
    <string>${SERVICE_LABEL}</string>
  </dict>
  <key>StartInterval</key>
  <integer>1800</integer>
  <key>StandardOutPath</key>
  <string>${LOG_DIR}/update.out.log</string>
  <key>StandardErrorPath</key>
  <string>${LOG_DIR}/update.err.log</string>
</dict>
</plist>
EOF

chmod 755 "$RUN_SCRIPT" "$UPDATE_SCRIPT"

launchctl bootout "gui/$(id -u)" "$SERVICE_PLIST" >/dev/null 2>&1 || true
launchctl bootout "gui/$(id -u)" "$UPDATE_PLIST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$SERVICE_PLIST"
launchctl bootstrap "gui/$(id -u)" "$UPDATE_PLIST"
launchctl enable "gui/$(id -u)/${SERVICE_LABEL}"
launchctl enable "gui/$(id -u)/${UPDATE_LABEL}"
launchctl kickstart -k "gui/$(id -u)/${SERVICE_LABEL}"

printf 'Installed %s and %s\n' "$SERVICE_LABEL" "$UPDATE_LABEL"
printf 'Environment file: %s\n' "$ENV_FILE"
printf 'Logs: %s\n' "$LOG_DIR"
