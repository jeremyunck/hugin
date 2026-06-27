#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${BOUW_DEV_ENV_FILE:-$HOME/.config/bouw-dev/env}"
LOG_DIR="${BOUW_DEV_LOG_DIR:-$REPO_DIR/.data/logs}"
DEV_HOME="${BOUW_DEV_HOME:-$HOME/.local/share/bouw-dev}"
DEPLOY_REPO_DIR="${BOUW_DEV_DEPLOY_REPO_DIR:-$DEV_HOME/repo}"

prepend_path() {
  local dir="$1"
  [[ -n "$dir" && -d "$dir" && ":$PATH:" != *":$dir:"* ]] || return 0
  PATH="$dir:$PATH"
}

mkdir -p "$LOG_DIR"

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:/usr/local/bin:/usr/bin:/bin:$PATH"
prepend_path "$HOME/.docker/bin"
prepend_path "/Applications/Docker.app/Contents/Resources/bin"
if [[ -n "${BOUW_SANDBOX_DOCKER_BIN:-}" ]]; then
  prepend_path "$(dirname "$BOUW_SANDBOX_DOCKER_BIN")"
fi
if [[ -z "${AGENT_HOME:-}" || "${AGENT_HOME}" == "$REPO_DIR" ]]; then
  export AGENT_HOME="$DEV_HOME"
fi
mkdir -p "$AGENT_HOME"

if [[ -x /opt/homebrew/opt/openjdk@21/bin/java ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
elif [[ -x /usr/local/opt/openjdk@21/bin/java ]]; then
  export JAVA_HOME="/usr/local/opt/openjdk@21"
else
  printf '[bouw-run] Java 21 was not found in the expected Homebrew locations.\n' >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

cd "$DEPLOY_REPO_DIR"

jar_path="$(find "$DEPLOY_REPO_DIR/backend/target" -maxdepth 1 -type f -name 'bouw-backend-*.jar' ! -name '*.original' | head -n 1)"
if [[ -z "$jar_path" ]]; then
  printf '[bouw-run] Built backend jar not found under %s/backend/target\n' "$DEPLOY_REPO_DIR" >&2
  exit 1
fi

exec "$JAVA_HOME/bin/java" -jar "$jar_path"
