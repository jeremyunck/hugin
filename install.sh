#!/usr/bin/env bash
# Raspberry Pi / Linux interactive installer for mcp-agent.
# Idempotent: re-running refreshes config without duplicating directories or services.
# Usage:  ./install.sh            (interactive install)
#         ./install.sh --uninstall (remove service + launcher; prompts before deleting AGENT_HOME)
set -euo pipefail

# ── helpers ──────────────────────────────────────────────────────────────────
info()    { printf '\033[1;34m[install]\033[0m %s\n' "$*"; }
success() { printf '\033[1;32m[install]\033[0m %s\n' "$*"; }
warn()    { printf '\033[1;33m[install]\033[0m %s\n' "$*"; }
die()     { printf '\033[1;31m[install]\033[0m %s\n' "$*" >&2; exit 1; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1
}

# ── uninstall path ────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--uninstall" ]]; then
  info "Stopping and removing mcp-agent service..."
  sudo systemctl disable --now mcp-agent 2>/dev/null || true
  sudo rm -f /etc/systemd/system/mcp-agent.service
  sudo systemctl daemon-reload
  sudo rm -f /usr/local/bin/mcp-agent
  success "Service and launcher removed."

  AGENT_HOME="${AGENT_HOME:-$HOME/.mcp-agent}"
  if [[ -d "$AGENT_HOME" ]]; then
    read -rp "Delete $AGENT_HOME (contains workspace + config)? [y/N] " confirm
    if [[ "${confirm,,}" == "y" ]]; then
      rm -rf "$AGENT_HOME"
      success "$AGENT_HOME deleted."
    else
      info "Kept $AGENT_HOME."
    fi
  fi
  exit 0
fi

# ── resolve paths ─────────────────────────────────────────────────────────────
AGENT_HOME="${AGENT_HOME:-$HOME/.mcp-agent}"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_USER="$(id -un)"

info "Installing mcp-agent to $AGENT_HOME"
info "Repo root: $REPO_DIR"

# ── 1. create directory tree ──────────────────────────────────────────────────
mkdir -p \
  "$AGENT_HOME/bin" \
  "$AGENT_HOME/config" \
  "$AGENT_HOME/venv" \
  "$AGENT_HOME/workspace" \
  "$AGENT_HOME/logs"

# ── 2. check / install toolchains ────────────────────────────────────────────
need_apt_update=false

# Java 21
if require_cmd java && java -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'; then
  info "Java 21+ already present."
else
  warn "Java 21 not found — installing Temurin JDK 21 via Adoptium apt repo..."
  sudo install -d -m 0755 /etc/apt/keyrings
  wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
    | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
  # shellcheck source=/dev/null
  . /etc/os-release
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb ${VERSION_CODENAME} main" \
    | sudo tee /etc/apt/sources.list.d/adoptium.list >/dev/null
  need_apt_update=true
fi

pkgs_to_install=()
require_cmd git      || pkgs_to_install+=(git)
require_cmd mvn      || pkgs_to_install+=(maven)
require_cmd python3  || pkgs_to_install+=(python3)

# Check python3-venv separately (python3 might be present but venv missing)
if ! python3 -c "import venv" 2>/dev/null; then
  pkgs_to_install+=(python3-venv)
fi

if [[ "${need_apt_update}" == "true" ]] || [[ ${#pkgs_to_install[@]} -gt 0 ]]; then
  sudo apt-get update -qq
fi

if [[ ${#pkgs_to_install[@]} -gt 0 ]]; then
  info "Installing: ${pkgs_to_install[*]}"
  sudo apt-get install -y "${pkgs_to_install[@]}"
fi

if ! require_cmd java && ! java -version 2>&1 | grep -qE '"(2[1-9]|[3-9][0-9])'; then
  sudo apt-get install -y temurin-21-jdk
fi

# ── 3. prompt: OpenRouter API key ─────────────────────────────────────────────
OPENROUTER_KEY=""
while [[ -z "$OPENROUTER_KEY" ]]; do
  read -rsp "OpenRouter API key (sk-or-v1-...): " OPENROUTER_KEY; echo
  if [[ -z "$OPENROUTER_KEY" ]]; then
    warn "The OpenRouter key is required (used for the LLM and web search). Try again."
  fi
done

# ── 4. prompt: Redis host ─────────────────────────────────────────────────────
read -rp "Redis host for long-term memory (leave blank to disable): " REDIS_HOST_INPUT

MEMORY_ENABLED=false
REDIS_HOST_VAL=""
REDIS_PORT_VAL=6379

if [[ -n "$REDIS_HOST_INPUT" ]]; then
  MEMORY_ENABLED=true
  REDIS_HOST_VAL="$REDIS_HOST_INPUT"
  read -rp "Redis port [6379]: " REDIS_PORT_INPUT
  REDIS_PORT_VAL="${REDIS_PORT_INPUT:-6379}"

  if [[ "$REDIS_HOST_INPUT" == "localhost" || "$REDIS_HOST_INPUT" == "127.0.0.1" ]]; then
    if ! require_cmd redis-server; then
      read -rp "Install Redis on this machine? [Y/n] " install_redis
      if [[ "${install_redis,,}" != "n" ]]; then
        sudo apt-get install -y redis-server
        sudo systemctl enable --now redis-server
      fi
    fi
  fi
fi

# ── 5. write agent.env ────────────────────────────────────────────────────────
cat > "$AGENT_HOME/agent.env" <<EOF
OPEN_ROUTER_API_KEY=${OPENROUTER_KEY}
MEMORY_ENABLED=${MEMORY_ENABLED}
REDIS_HOST=${REDIS_HOST_VAL}
REDIS_PORT=${REDIS_PORT_VAL}
# AGENT_API_KEY=                 # uncomment + set to require X-API-Key on /api/agent/**
EOF
chmod 600 "$AGENT_HOME/agent.env"
info "Wrote $AGENT_HOME/agent.env (chmod 600)"

# ── 6. write config/application.yml ──────────────────────────────────────────
cat > "$AGENT_HOME/config/application.yml" <<EOF
mcp:
  config-file: ${AGENT_HOME}/config/mcp-servers.json
search:
  openrouter-script: ${AGENT_HOME}/bin/openrouter-search-mcp.py
agent:
  tools:
    workspace-root: ${AGENT_HOME}/workspace
EOF
info "Wrote $AGENT_HOME/config/application.yml"

# Seed mcp-servers.json from example if not present
if [[ ! -f "$AGENT_HOME/config/mcp-servers.json" ]]; then
  if [[ -f "$REPO_DIR/mcp-servers.example.json" ]]; then
    cp "$REPO_DIR/mcp-servers.example.json" "$AGENT_HOME/config/mcp-servers.json"
    info "Seeded $AGENT_HOME/config/mcp-servers.json from mcp-servers.example.json"
  else
    echo '{"mcpServers":{}}' > "$AGENT_HOME/config/mcp-servers.json"
    info "Created empty $AGENT_HOME/config/mcp-servers.json"
  fi
fi

# ── 7. python venv for web search ────────────────────────────────────────────
info "Setting up Python venv for the web-search MCP server..."
python3 -m venv "$AGENT_HOME/venv"
"$AGENT_HOME/venv/bin/pip" install --no-cache-dir --quiet mcp
cp "$REPO_DIR/openrouter-search-mcp.py" "$AGENT_HOME/bin/openrouter-search-mcp.py"
info "Python venv ready; openrouter-search-mcp.py copied to bin/"

# ── 8. build fat jars ────────────────────────────────────────────────────────
info "Building fat jars (this may take a few minutes on a Pi)..."
MAVEN_OPTS="${MAVEN_OPTS:--Xmx512m}" \
  mvn -f "$REPO_DIR/pom.xml" \
      -pl mcp-integration,agent-terminal -am \
      clean package -DskipTests -q

cp "$REPO_DIR"/mcp-integration/target/mcp-integration-*.jar  "$AGENT_HOME/bin/mcp-integration.jar"
cp "$REPO_DIR"/agent-terminal/target/agent-terminal-*.jar     "$AGENT_HOME/bin/agent-terminal.jar"
success "Jars built and copied to $AGENT_HOME/bin/"

# ── 9. install mcp-agent launcher ────────────────────────────────────────────
LAUNCHER_SRC="$REPO_DIR/scripts/mcp-agent"
sudo install -m 0755 "$LAUNCHER_SRC" /usr/local/bin/mcp-agent
info "Installed mcp-agent launcher to /usr/local/bin/mcp-agent"

# ── 10. install + enable systemd service ─────────────────────────────────────
SERVICE_SRC="$REPO_DIR/scripts/mcp-agent.service"
SERVICE_DEST=/etc/systemd/system/mcp-agent.service

# Substitute placeholders in the template
sed \
  -e "s|<install-user>|${INSTALL_USER}|g" \
  -e "s|<AGENT_HOME>|${AGENT_HOME}|g" \
  "$SERVICE_SRC" \
  | sudo tee "$SERVICE_DEST" >/dev/null

sudo systemctl daemon-reload
sudo systemctl enable --now mcp-agent
info "systemd service installed and started."

# ── 11. wait for health ───────────────────────────────────────────────────────
info "Waiting for the agent server to become healthy..."
attempts=0
max_attempts=30
until curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; do
  attempts=$((attempts + 1))
  if [[ $attempts -ge $max_attempts ]]; then
    warn "Server did not become healthy within ${max_attempts}s."
    warn "Check logs: mcp-agent logs"
    break
  fi
  sleep 1
done

if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
  success "Agent server is healthy on http://localhost:8080"
fi

# ── done ──────────────────────────────────────────────────────────────────────
cat <<'MSG'

Installed. The agent server is running on http://localhost:8080

  Start chatting:        mcp-agent
  Hit the API directly:  curl -X POST http://localhost:8080/api/agent/chat \
                           -H "Content-Type: application/json" \
                           -d '{"prompt": "Hello!"}'
  Service status/logs:   mcp-agent status   |   mcp-agent logs
  Reconfigure:           mcp-agent config
  Uninstall:             ./install.sh --uninstall

MSG
