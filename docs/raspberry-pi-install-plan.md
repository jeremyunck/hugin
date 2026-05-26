# Raspberry Pi Install Plan

Plan for turning this project into an **easily installable, Ollama-style agent
appliance** that lives on a Raspberry Pi. The user runs an interactive installer
once, then drives everything from a single command — exactly like
`ollama run`.

This work is sequenced **before** the
[`cloud-agent-deployment-plan.md`](./cloud-agent-deployment-plan.md). Cloud
agents (per-repo clones, `/api/agents`, the per-agent workspace refactor) are
deferred; this plan delivers the local, single-machine experience first. The
two share the same `$AGENT_HOME` base-of-operations idea, so this is a clean
foundation to build cloud agents on later.

## 1. Goal & target UX (the "Ollama experience")

Ollama's appeal is two commands: an installer (`curl … | sh`) and a runner
(`ollama serve`) that *just works* — it transparently starts a background
service. We want the same shape:

```bash
# 1. One-time interactive install (clones, builds, prompts for secrets, installs a service)
./install.sh

# 2. One command to use it from then on — starts the service if needed, prints API usage
mcp-agent
```

After install:

- The **agent server** (`mcp-integration`, port 8080) runs as a persistent
  background **systemd service**, started on boot. This is the equivalent of
  `ollama serve`.
- **`mcp-agent`** is a launcher on `PATH`. With no arguments it ensures the
  service is up, waits for health, then prints how to hit the HTTP API.
- Because the server is always listening on `:8080`, the user **hits the HTTP
  API directly** (`POST /api/agent/chat` or the streaming `/api/agent/stream`).

The installer prompts for exactly two things, per the new requirements:

1. **OpenRouter API key** — written so the server (LLM + web search + embeddings)
   picks it up via `OPEN_ROUTER_API_KEY`.
2. **Redis host** — left blank ⇒ long-term memory stays disabled
   (`MEMORY_ENABLED=false`); a value ⇒ memory enabled with that host.

## 2. Decisions taken

These shape everything below:

1. **Native install, not Docker, for the Pi.** Docker is **not needed** for this
   path and adds cost we don't want on a Pi: image build/pull time and a second
   memory-hungry layer.
   The default LLM is **remote OpenRouter over HTTPS**, so there is no heavy
   local model to containerize — the only runtime dependencies are a **JRE 21**
   and **python3** (for web search). A native install + a systemd service is
   lighter, boots faster, and matches Ollama's own native-install model.
   - The existing `Dockerfile` / `docker-compose.yml` / `.dockerignore` are
     **kept as-is** — they remain the artifact for the *cloud* deployment path
     (the dockerization plan). We are simply **not using Docker for the Pi
     install**, not deleting it. (If the project later decides the cloud path is
     dead, those three files can be removed in a separate change.)
2. **Persistent systemd service** for the server (auto-start on boot, restart on
   crash), mirroring how Ollama installs `ollama.service`. The `mcp-agent`
   launcher is a thin client wrapper around it.
3. **Build from source on the Pi** as the default install path (the repo is
   already cloned to run `install.sh`). A future enhancement is to attach
   prebuilt fat jars to a GitHub Release and let the installer download them to
   skip the Maven build on low-RAM Pis (see §10).
4. **Config via a layered override file + an env file**, never by editing the
   packaged `application.yml`. Secrets/toggles go in an `EnvironmentFile`
   (`$AGENT_HOME/agent.env`) read by systemd; absolute paths go in
   `$AGENT_HOME/config/application.yml` loaded with
   `spring.config.additional-location`. This avoids relaxed-binding ambiguity
   and lets the user re-run `mcp-agent config` without a rebuild.

## 3. Prerequisites & target platform

- **Hardware:** Raspberry Pi 4 / 5 (arm64), ≥ 2 GB RAM recommended. A Pi 5 / 4 GB
  is comfortable; building with Maven on a 1 GB Pi is tight — see §10 for the
  prebuilt-jar fallback.
- **OS:** Raspberry Pi OS (Debian Bookworm) 64-bit, or any Debian/Ubuntu arm64.
- **Java 21:** required by the build and runtime. Bookworm ships only JDK 17, so
  the installer adds the **Adoptium (Temurin) apt repo** and installs
  `temurin-21-jdk` when `java -version` is < 21.
- **python3 + `mcp` pip package:** needed by the default OpenRouter web-search
  MCP server (`openrouter-search-mcp.py`). The installer creates a venv and
  `pip install mcp` into it.
- **git + Maven:** git to clone; Maven (`apt-get install -y maven`, Bookworm has
  3.8.x) to build. Both are installed by the installer if missing.
- **Network egress** to `https://openrouter.ai` (LLM, web search, embeddings).
- **(Optional) Redis 6+** — only if the user supplies a Redis host. Can be the
  same Pi (`apt-get install -y redis-server`) or a remote host. Not installed
  unless requested.

## 4. Base-of-operations directory layout

A single home directory, location from `AGENT_HOME` (default `~/.mcp-agent`).
This intentionally matches the layout in the cloud-agent plan so the two merge
cleanly later:

```
$AGENT_HOME/
  bin/
    mcp-integration.jar      # the agent server fat jar
    openrouter-search-mcp.py # bundled web-search MCP server script
  config/
    application.yml          # absolute-path overrides (additional-location)
    mcp-servers.json         # MCP server registry (mcp.config-file points here)
  venv/                      # python venv with the `mcp` package for web search
  workspace/                 # agent.tools.workspace-root (file/shell sandbox)
  logs/                      # reserved (systemd uses journald by default)
  agent.env                  # EnvironmentFile: secrets + toggles (chmod 600)
```

- `config/application.yml` is layered on top of the packaged config via
  `--spring.config.additional-location=file:$AGENT_HOME/config/application.yml`.
- `agent.env` holds `OPEN_ROUTER_API_KEY`, `MEMORY_ENABLED`, `REDIS_HOST`,
  `REDIS_PORT`, and optionally `AGENT_API_KEY`. It is `chmod 600` because it
  contains the API key.
- `workspace/` is the pinned `agent.tools.workspace-root` so the built-in
  file/shell tools have a stable, absolute sandbox (relative/`~` are *not*
  expanded for that property).

## 5. The interactive installer (`install.sh`)

A POSIX/bash script at the repo root, run as a normal user (it uses `sudo` only
for apt + writing the systemd unit). Idempotent — re-running re-provisions
without duplicating anything.

Steps, in order:

1. **Resolve `AGENT_HOME`** (`${AGENT_HOME:-$HOME/.mcp-agent}`) and create the
   directory tree from §4.
2. **Check/install toolchains** (only what's missing):
   - **Java 21:** if `java -version` reports < 21, add the Adoptium repo and
     install `temurin-21-jdk`:
     ```bash
     sudo install -d -m 0755 /etc/apt/keyrings
     wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
       | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
     . /etc/os-release
     echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $VERSION_CODENAME main" \
       | sudo tee /etc/apt/sources.list.d/adoptium.list
     sudo apt-get update && sudo apt-get install -y temurin-21-jdk
     ```
   - **git, maven, python3, python3-venv** via `apt-get install -y` if missing.
3. **Prompt for the OpenRouter API key** (the first required prompt):
   ```bash
   read -rsp "OpenRouter API key (sk-or-v1-...): " OPENROUTER_KEY; echo
   ```
   Read silently (`-s`) since it's a secret. Re-prompt if empty (the key is
   required — without it the LLM and default web search won't work).
4. **Prompt for the Redis host** (the second required prompt; blank ⇒ disabled):
   ```bash
   read -rp "Redis host for long-term memory (leave blank to disable): " REDIS_HOST_INPUT
   if [ -n "$REDIS_HOST_INPUT" ]; then
     read -rp "Redis port [6379]: " REDIS_PORT_INPUT
     MEMORY_ENABLED=true
   else
     MEMORY_ENABLED=false
   fi
   ```
   When a host is given, memory is enabled; embeddings already work off the same
   `OPEN_ROUTER_API_KEY` (the default `embedding.*` config targets OpenRouter),
   so no extra prompt is needed. Optionally detect "user wants Redis on this Pi"
   and offer `apt-get install -y redis-server` when the host is `localhost`.
5. **Write `$AGENT_HOME/agent.env`** (chmod 600):
   ```
   OPEN_ROUTER_API_KEY=sk-or-v1-...
   MEMORY_ENABLED=false            # or true
   REDIS_HOST=                     # set only when provided
   REDIS_PORT=6379
   # AGENT_API_KEY=                # optional; see §8 security
   ```
   All of these are consumed by the **existing** `${VAR:default}` placeholders in
   `mcp-integration/src/main/resources/application.yml`, so they bind reliably.
6. **Write `$AGENT_HOME/config/application.yml`** with the absolute paths the
   service needs when its working dir is `$AGENT_HOME` rather than the repo root:
   ```yaml
   mcp:
     config-file: ${AGENT_HOME}/config/mcp-servers.json
   search:
     openrouter-script: ${AGENT_HOME}/bin/openrouter-search-mcp.py
   agent:
     tools:
       workspace-root: ${AGENT_HOME}/workspace
   ```
   (The installer substitutes the resolved `$AGENT_HOME` literally when writing
   the file.) Seed `config/mcp-servers.json` from `mcp-servers.example.json` if
   absent, or leave it out and rely on the `search.provider` auto-registration.
7. **Set up the python venv for web search:**
   ```bash
   python3 -m venv "$AGENT_HOME/venv"
   "$AGENT_HOME/venv/bin/pip" install --no-cache-dir mcp
   ```
   Copy `openrouter-search-mcp.py` into `$AGENT_HOME/bin/`. The service launcher
   prepends `$AGENT_HOME/venv/bin` to `PATH` so the JVM's child process resolves
   `python3` with `mcp` installed.
8. **Build the fat jar** (default path) and copy it into `bin/`:
   ```bash
   mvn -pl mcp-integration -am clean package -DskipTests
   cp mcp-integration/target/mcp-integration-*.jar "$AGENT_HOME/bin/mcp-integration.jar"
   ```
   On low-RAM Pis, cap the build heap (`MAVEN_OPTS="-Xmx512m"`) or use the
   prebuilt-release path (§10).
9. **Install the `mcp-agent` launcher** (§6) to `/usr/local/bin/mcp-agent`
   (`sudo install -m 0755`).
10. **Install + enable the systemd service** (§7), then `systemctl daemon-reload`
    and `systemctl enable --now mcp-agent`.
11. **Wait for health** (`curl -sf http://localhost:8080/actuator/health`) and
    print next steps:
    ```
    Installed. The agent server is running on http://localhost:8080
    Check it's up:         mcp-agent
    Hit the API directly:  curl -X POST http://localhost:8080/api/agent/chat ...
    Service status/logs:   mcp-agent status   |   mcp-agent logs
    Reconfigure:           mcp-agent config
    ```

## 6. The `mcp-agent` launcher command

A small shell script on `PATH` that gives the Ollama-style single-command UX.
Subcommands:

| Command | Behavior |
| --- | --- |
| `mcp-agent` / `mcp-agent run` | Ensure the service is active (`systemctl start mcp-agent` if not), poll `/actuator/health` until ready (timeout ~30s), then print how to hit the HTTP API. |
| `mcp-agent serve` | Run the **server** in the foreground (`java -jar … mcp-integration.jar` with the config override). This is exactly what the systemd `ExecStart` calls; also handy for debugging without systemd. |
| `mcp-agent start` / `stop` / `restart` / `status` | Thin `systemctl … mcp-agent` wrappers. |
| `mcp-agent logs` | `journalctl -u mcp-agent -f`. |
| `mcp-agent config` | Re-run the API-key / Redis prompts (§5 steps 3–6), rewrite `agent.env`, `restart` the service. |
| `mcp-agent uninstall` | §9. |

The launcher loads `$AGENT_HOME/agent.env` so it can surface the right `curl`
invocation — including the `X-API-Key` header when `AGENT_API_KEY` is set:

```bash
[ -n "$AGENT_API_KEY" ] && auth='-H "X-API-Key: $AGENT_API_KEY"'   # forwarded to /api/agent/**
```

This is what makes a single `mcp-agent` invocation "start the service and tell
you how to call it."

## 7. systemd service

`/etc/systemd/system/mcp-agent.service`, running as the installing user so the
workspace and config are owned correctly:

```ini
[Unit]
Description=MCP Agent server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=<install-user>
Environment=AGENT_HOME=<resolved AGENT_HOME>
EnvironmentFile=<AGENT_HOME>/agent.env
# Prepend the venv so the web-search MCP subprocess finds python3 + mcp.
Environment=PATH=<AGENT_HOME>/venv/bin:/usr/bin:/bin
WorkingDirectory=<AGENT_HOME>
ExecStart=/usr/bin/java -jar <AGENT_HOME>/bin/mcp-integration.jar \
  --spring.config.additional-location=file:<AGENT_HOME>/config/application.yml
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

- `EnvironmentFile` injects the OpenRouter key + memory toggles from §5.
- `Restart=on-failure` gives the crash-resilience users expect from a daemon.
- Logs go to journald, surfaced by `mcp-agent logs`.
- A future option is a **user service** (`systemctl --user`, requires
  `loginctl enable-linger`) to avoid `sudo`; the system service is chosen here
  to match Ollama and to start on boot without a logged-in session.

## 8. Configuration wiring summary

| Setting | How it's set on the Pi | Source property |
| --- | --- | --- |
| OpenRouter key | `agent.env` → `OPEN_ROUTER_API_KEY` | `llm.providers.openrouter.api-key`, `embedding.api-key`, web-search env |
| Long-term memory on/off | `agent.env` → `MEMORY_ENABLED` (blank Redis ⇒ false) | `memory.enabled` |
| Redis host/port | `agent.env` → `REDIS_HOST` / `REDIS_PORT` | `spring.data.redis.*` |
| Optional API auth | `agent.env` → `AGENT_API_KEY` | `agent.api-key` (sent as `X-API-Key`) |
| MCP config path | `config/application.yml` (absolute) | `mcp.config-file` |
| Web-search script path | `config/application.yml` (absolute) | `search.openrouter-script` |
| Tool sandbox root | `config/application.yml` (absolute) | `agent.tools.workspace-root` |

**Security notes (carried from the existing docs):**
- `agent.api-key` is blank by default, leaving `/api/agent/**` open. On a
  single-user home Pi reachable only on the LAN that is usually fine; the
  installer can optionally prompt to set `AGENT_API_KEY` when the user wants the
  API exposed beyond localhost.
- Built-in file/shell tools (`agent.tools.enabled=true`) are powerful by design
  (this is "an agent that can do things on the Pi"); they are confined to
  `$AGENT_HOME/workspace`. Document the tradeoff; allow `AGENT_TOOLS_ENABLED=false`
  for users who want a chat-only assistant.
- `agent.env` is `chmod 600`.

## 9. Uninstall

`mcp-agent uninstall` (and an `--uninstall` flag on `install.sh`):

```bash
sudo systemctl disable --now mcp-agent
sudo rm /etc/systemd/system/mcp-agent.service && sudo systemctl daemon-reload
sudo rm /usr/local/bin/mcp-agent
# Leave $AGENT_HOME by default (contains workspace + config); remove with a prompt.
```

Prompt before deleting `$AGENT_HOME` so the user doesn't lose workspace files or
config inadvertently.

## 10. Build vs. prebuilt jars (low-RAM Pis)

Maven building both modules on a 1 GB Pi can thrash. Two mitigations, in order
of preference:

1. **Now:** `MAVEN_OPTS="-Xmx512m"` and `-DskipTests` (already in the build
   command). Acceptable on 2 GB+.
2. **Follow-up:** a CI job (extend `.github/workflows/`) that builds the fat
   jar and attaches it to a **GitHub Release**. `install.sh` then offers
   "download prebuilt jar" vs "build from source", downloading
   `mcp-integration.jar` for the Pi's architecture
   (the jar is pure-JVM, so one artifact works on any arch). This removes
   Maven/JDK-build from the Pi entirely — only a JRE is needed at runtime.

## 11. Implementation checklist

New files / changes, smallest-blast-radius first:

1. **`install.sh`** (repo root) — §5. The bulk of the work.
2. **`mcp-agent`** launcher script (repo root, e.g. `scripts/mcp-agent`) — §6.
   Installed to `/usr/local/bin`.
3. **`scripts/mcp-agent.service`** systemd unit template — §7. The installer
   substitutes `<install-user>` / `<AGENT_HOME>` and writes it into
   `/etc/systemd/system/`.
4. **README** — add a "Raspberry Pi install" section mirroring the Ollama flow
   (`./install.sh` then `mcp-agent`), and note the API is always available on
   `:8080` for direct use.
5. **(Optional, §10)** Release-build CI workflow + a download branch in
   `install.sh`.

No Java code changes are required for this plan — it is packaging + config +
service wiring around the existing server. (The cloud-agent plan is where the
Java refactors live.)

## 12. Open questions / out of scope

- **Prebuilt-jar release pipeline** (§10) — recommended follow-up, not required
  for a working install.
- **Optional `AGENT_API_KEY` prompt** — whether to prompt for it during install
  or leave it to `mcp-agent config`. Default: leave the API open on the LAN,
  document how to lock it down.
- **Installing Redis locally** — the installer can offer `apt-get install
  redis-server` when the user enters `localhost`; otherwise it assumes the host
  is already reachable.
- **Auto-update** (`mcp-agent upgrade` that re-pulls/re-builds) — deferred.
- **Cloud agents** — the entire [`cloud-agent-deployment-plan.md`](./cloud-agent-deployment-plan.md)
  (per-agent workspaces, repo cloning, `/api/agents`) remains the next phase
  after this install experience ships.
