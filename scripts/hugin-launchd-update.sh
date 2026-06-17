#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${HUGIN_DEV_ENV_FILE:-$HOME/.config/hugin-dev/env}"
SERVICE_LABEL="${HUGIN_DEV_SERVICE_LABEL:-com.jnku.hugin.repo-server}"
SERVICE_PLIST="${HUGIN_DEV_SERVICE_PLIST:-$HOME/Library/LaunchAgents/${SERVICE_LABEL}.plist}"
UPDATE_LOG_DIR="${HUGIN_DEV_LOG_DIR:-$REPO_DIR/.data/logs}"

info() { printf '[hugin-update] %s\n' "$*"; }
warn() { printf '[hugin-update] %s\n' "$*" >&2; }
kickstart_service() {
  local target="gui/$(id -u)/${SERVICE_LABEL}"
  local attempt
  for attempt in {1..10}; do
    if launchctl kickstart -k "$target" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done

  warn "Unable to kickstart ${SERVICE_LABEL} after reloading ${SERVICE_PLIST}."
  return 1
}

mkdir -p "$UPDATE_LOG_DIR"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:$PATH"
export AGENT_HOME="${AGENT_HOME:-$REPO_DIR}"

if [[ -x /opt/homebrew/opt/openjdk@21/bin/java ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
elif [[ -x /usr/local/opt/openjdk@21/bin/java ]]; then
  export JAVA_HOME="/usr/local/opt/openjdk@21"
else
  warn "Java 21 was not found in the expected Homebrew locations."
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

cd "$REPO_DIR"

current_branch="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$current_branch" != "main" ]]; then
  warn "Skipping update because checkout is on branch '$current_branch'."
  exit 0
fi

if ! git diff --quiet --ignore-submodules -- || ! git diff --cached --quiet --ignore-submodules --; then
  warn "Skipping update because the checkout has tracked local changes."
  exit 0
fi

info "Fetching origin/main..."
git fetch origin main --prune

local_head="$(git rev-parse HEAD)"
remote_head="$(git rev-parse origin/main)"

if [[ "$local_head" == "$remote_head" ]]; then
  info "No changes to deploy."
  exit 0
fi

info "Fast-forwarding to ${remote_head:0:7}..."
git pull --ff-only origin main

info "Rebuilding frontend and backend artifacts..."
# The backend Maven build runs the frontend build first, then packages the
# compiled web assets into the jar served by the detached launchd process.
MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m}" mvn -q -DskipTests package

info "Reloading launchd service ${SERVICE_LABEL} in detached mode..."
launchctl bootout "gui/$(id -u)" "$SERVICE_PLIST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$SERVICE_PLIST"
kickstart_service

info "Update complete."
