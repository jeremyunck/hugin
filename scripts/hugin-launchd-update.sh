#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${HUGIN_DEV_ENV_FILE:-$HOME/.config/hugin-dev/env}"
SERVICE_LABEL="${HUGIN_DEV_SERVICE_LABEL:-com.jnku.hugin.repo-server}"
SERVICE_PLIST="${HUGIN_DEV_SERVICE_PLIST:-$HOME/Library/LaunchAgents/${SERVICE_LABEL}.plist}"
UPDATE_LOG_DIR="${HUGIN_DEV_LOG_DIR:-$REPO_DIR/.data/logs}"
DEV_HOME="${HUGIN_DEV_HOME:-$HOME/.local/share/hugin-dev}"
DEPLOY_REPO_DIR="${HUGIN_DEV_DEPLOY_REPO_DIR:-$DEV_HOME/repo}"
REPO_URL="${HUGIN_DEV_REPO_URL:-}"
SANDBOX_IMAGE="${HUGIN_SANDBOX_IMAGE:-hugin-agent-sandbox:latest}"

info() { printf '[hugin-update] %s\n' "$*"; }
warn() { printf '[hugin-update] %s\n' "$*" >&2; }
stash_deployment_changes() {
  local stash_name="hugin-deploy-autostash-$(date +%s)"
  git stash push --include-untracked --message "$stash_name" >/dev/null
  info "Saved deployment checkout changes to stash '${stash_name}'."
}
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
if [[ -z "${AGENT_HOME:-}" || "${AGENT_HOME}" == "$REPO_DIR" ]]; then
  export AGENT_HOME="$DEV_HOME"
fi
mkdir -p "$AGENT_HOME"
mkdir -p "$DEPLOY_REPO_DIR"

if [[ -x /opt/homebrew/opt/openjdk@21/bin/java ]]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
elif [[ -x /usr/local/opt/openjdk@21/bin/java ]]; then
  export JAVA_HOME="/usr/local/opt/openjdk@21"
else
  warn "Java 21 was not found in the expected Homebrew locations."
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"

if [[ ! -f "$SERVICE_PLIST" ]]; then
  warn "LaunchAgent plist not found at ${SERVICE_PLIST}."
  exit 1
fi

if [[ ! -d "$DEPLOY_REPO_DIR/.git" ]]; then
  if [[ -z "$REPO_URL" ]]; then
    REPO_URL="$(git -C "$REPO_DIR" remote get-url origin)"
  fi
  info "Cloning deployment checkout into ${DEPLOY_REPO_DIR}..."
  git clone "$REPO_URL" "$DEPLOY_REPO_DIR" || {
    warn "Could not clone ${REPO_URL} into ${DEPLOY_REPO_DIR}."
    exit 1
  }
fi

cd "$DEPLOY_REPO_DIR"

info "Fetching origin/main..."
git fetch origin main --prune

current_branch="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$current_branch" != "main" ]]; then
  info "Switching deployment checkout to main..."
  git checkout main >/dev/null 2>&1 || git checkout -B main origin/main
fi

local_head="$(git rev-parse HEAD)"
remote_head="$(git rev-parse origin/main)"
built_jar=""
if [[ -d "$DEPLOY_REPO_DIR/backend/target" ]]; then
  built_jar="$(find "$DEPLOY_REPO_DIR/backend/target" -maxdepth 1 -type f -name 'hugin-backend-*.jar' ! -name '*.original' | head -n 1)"
fi
built_sandbox_image=false
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 \
  && docker image inspect "$SANDBOX_IMAGE" >/dev/null 2>&1; then
  built_sandbox_image=true
fi

if [[ "$local_head" == "$remote_head" ]] \
  && [[ -n "$built_jar" ]] \
  && [[ "$built_sandbox_image" == "true" ]] \
  && git diff --quiet --ignore-submodules -- \
  && git diff --cached --quiet --ignore-submodules --; then
  info "No changes to deploy."
  exit 0
fi

if ! git diff --quiet --ignore-submodules -- || ! git diff --cached --quiet --ignore-submodules --; then
  info "Attempting fast-forward for dedicated deployment checkout..."
  if git merge --ff-only "$remote_head"; then
    :
  else
    stash_deployment_changes
    warn "Deployment checkout could not fast-forward cleanly; resetting to ${remote_head:0:7}."
    git reset --hard "$remote_head"
  fi
else
  info "Fast-forwarding to ${remote_head:0:7}..."
  git merge --ff-only "$remote_head"
fi

info "Rebuilding frontend and backend artifacts..."
# The backend Maven build runs the frontend build first, then packages the
# compiled web assets into the jar served by the detached launchd process.
MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m}" mvn -q -DskipTests package

if ! command -v docker >/dev/null 2>&1; then
  warn "Docker CLI not found on PATH. Install Docker Desktop; project/GitHub chats require ${SANDBOX_IMAGE}."
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  warn "Docker daemon not reachable. Start Docker Desktop so ${SANDBOX_IMAGE} can be rebuilt."
  exit 1
fi

info "Building project-chat sandbox image ${SANDBOX_IMAGE}..."
docker build -t "$SANDBOX_IMAGE" "$DEPLOY_REPO_DIR/docker/sandbox"

info "Reloading launchd service ${SERVICE_LABEL} in detached mode..."
launchctl bootout "gui/$(id -u)" "$SERVICE_PLIST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$SERVICE_PLIST"
sleep 1
kickstart_service

info "Update complete."
