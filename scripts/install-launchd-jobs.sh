#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAUNCH_AGENTS_DIR="$HOME/Library/LaunchAgents"
ENV_DIR="$HOME/.config/hugin-dev"
ENV_FILE="$ENV_DIR/env"
LOG_DIR="$REPO_DIR/.data/logs"
DEV_HOME="${HUGIN_DEV_HOME:-$HOME/.local/share/hugin-dev}"
SERVICE_LABEL="com.jnku.hugin.repo-server"
UPDATE_LABEL="com.jnku.hugin.repo-autoupdate"
SERVICE_PLIST="$LAUNCH_AGENTS_DIR/${SERVICE_LABEL}.plist"
UPDATE_PLIST="$LAUNCH_AGENTS_DIR/${UPDATE_LABEL}.plist"
RUN_SCRIPT="$REPO_DIR/scripts/hugin-launchd-run.sh"
UPDATE_SCRIPT="$REPO_DIR/scripts/hugin-launchd-update.sh"

mkdir -p "$LAUNCH_AGENTS_DIR" "$ENV_DIR" "$LOG_DIR" "$DEV_HOME"

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
append_if_set GOOGLE_OAUTH_CLIENT_SECRETS_FILE "${GOOGLE_OAUTH_CLIENT_SECRETS_FILE:-}"
append_if_set GOOGLE_OAUTH_TOKEN_DIR "${GOOGLE_OAUTH_TOKEN_DIR:-}"
append_if_set GOOGLE_APPLICATION_CREDENTIALS "${GOOGLE_APPLICATION_CREDENTIALS:-}"
append_if_set GOOGLE_IMPERSONATE_USER "${GOOGLE_IMPERSONATE_USER:-}"
append_if_set GOOGLE_DEFAULT_SHARE_WITH "${GOOGLE_DEFAULT_SHARE_WITH:-}"
append_if_set REDIS_HOST "${REDIS_HOST:-}"
append_if_set REDIS_PORT "${REDIS_PORT:-}"
append_if_set MEMORY_ENABLED "${MEMORY_ENABLED:-}"
append_if_set GITHUB_TOKEN "${GITHUB_TOKEN:-}"
append_if_set NEW_RELIC_ENABLED "${NEW_RELIC_ENABLED:-}"
append_if_set NEW_RELIC_TOKEN "${NEW_RELIC_TOKEN:-}"
append_if_set CLOUD_AGENTS_ENABLED "${CLOUD_AGENTS_ENABLED:-}"
append_if_set SCHEDULER_DEFAULT_ZONE "${SCHEDULER_DEFAULT_ZONE:-}"
append_if_set SCHEDULER_ENABLED "${SCHEDULER_ENABLED:-}"
append_if_set AGENT_HOME "${AGENT_HOME:-$DEV_HOME}"
append_if_set HUGIN_DEV_HOME "$DEV_HOME"
append_if_set HUGIN_DEV_ENV_FILE "$ENV_FILE"
append_if_set HUGIN_DEV_LOG_DIR "$LOG_DIR"
append_if_set HUGIN_DEV_SERVICE_LABEL "$SERVICE_LABEL"
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
    <key>HUGIN_DEV_ENV_FILE</key>
    <string>${ENV_FILE}</string>
    <key>HUGIN_DEV_LOG_DIR</key>
    <string>${LOG_DIR}</string>
    <key>HUGIN_DEV_SERVICE_LABEL</key>
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
    <key>HUGIN_DEV_ENV_FILE</key>
    <string>${ENV_FILE}</string>
    <key>HUGIN_DEV_LOG_DIR</key>
    <string>${LOG_DIR}</string>
    <key>HUGIN_DEV_SERVICE_LABEL</key>
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
