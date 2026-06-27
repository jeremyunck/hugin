[![Bouw banner](img/readme_banner.png)](/img/readme_banner.png)

# Bouw

Bouw is a Spring Boot + React personal assistant. It talks to any OpenAI-compatible chat
endpoint, exposes a web UI, and ships with local tools for file access, shell commands, web
search, Google Workspace, email, scheduling, optional repo-local skills, and optional
Redis-backed memory.

## Docker quickstart

Run the whole stack — app, PostgreSQL, and Redis — with Docker. No host Java, Node, Maven,
PostgreSQL, or Redis required.

**Prerequisites:** [Docker Desktop](https://www.docker.com/products/docker-desktop/) (macOS/Windows)
or Docker Engine + the Compose plugin (Linux).

```bash
git clone https://github.com/jeremyunck/bouw.git
cd bouw
cp .env.example .env
# Edit .env and add an LLM key:
#   - OPEN_ROUTER_API_KEY=sk-or-v1-...   (keep LLM_PROVIDER=openrouter), or
#   - OPENAI_API_KEY=sk-...              (set LLM_PROVIDER=openai and LLM_MODEL=gpt-4o-mini)
docker compose up --build
```

Then open **http://localhost:8080**.

Compose starts three services and creates everything automatically:

- **bouw** — the app (multi-stage build: React frontend → Spring Boot jar → slim Java 21 runtime)
- **postgres** — creates the `bouw` database, user, and schema on first boot (no manual SQL)
- **redis** — available to the app for optional long-term memory

The app waits for PostgreSQL to pass its healthcheck before starting, so the first `up` may take a
few seconds longer while the database initializes.

**Login:** authentication is email + password with a one-time email verification code. New users
click **Create an account**, enter an email and password (twice), then confirm the 6-digit code that
is emailed to them; every subsequent login re-confirms a fresh code. Codes are delivered with
[Resend](https://resend.com) — set `RESEND_API_KEY` and `RESEND_FROM` in `.env`. **When Resend is
not configured, the code is written to the app logs instead of being emailed** (`docker compose logs
bouw`), which keeps the localhost demo usable without a Resend account; do not rely on this beyond
local development. All accounts created this way are `ROLE_USER`.

A bootstrap account is still created on first start from `AUTH_BOOTSTRAP_USERNAME` /
`AUTH_BOOTSTRAP_PASSWORD` in `.env` (the quickstart ships `admin` / `change-me`). Because
`change-me` is a well-known default, Bouw would normally **refuse to start** with it; the
quickstart sets `AUTH_BOOTSTRAP_ALLOW_INSECURE_PASSWORD=true` so the localhost demo boots while
logging a loud warning. **Before exposing Bouw beyond localhost**, set a strong, unique
`AUTH_BOOTSTRAP_PASSWORD` and remove `AUTH_BOOTSTRAP_ALLOW_INSECURE_PASSWORD` (it defaults to
`false`). See [Production credentials](#production-credentials).

**Stop the stack:**

```bash
docker compose down
```

**Reset all local data** (database, Redis, agent home, and workspace volumes):

```bash
docker compose down -v
```

### Notes and limitations

- **LLM credentials are required for the agent to chat.** The stack boots without a key, but you
  must add `OPEN_ROUTER_API_KEY` (or `OPENAI_API_KEY` with `LLM_PROVIDER=openai`) for model calls to
  succeed.
- **Sandbox mode is disabled by default in the quickstart** (`SANDBOX_ENABLED=false`). Bouw's
  per-session Docker sandboxes run agent-issued shell commands inside Docker containers and need
  access to a Docker daemon. To enable them, layer in the override:

  ```bash
  docker compose -f docker-compose.yml -f docker-compose.sandbox.yml up --build
  ```

  > ⚠️ **Security: the override mounts the host Docker socket** (`/var/run/docker.sock`), which
  > grants the container control of the host's Docker daemon — effectively root-equivalent on the
  > host. Only enable this on trusted local/self-hosted machines you control. Docker socket access is
  > never enabled silently; when sandbox mode starts, Bouw logs a `SANDBOX SECURITY NOTICE` (and
  > flags it explicitly when the socket is mounted).

- **Secrets stay out of the image.** Keys are read from `.env` at runtime; `.env` is gitignored and
  never baked into the build.

### Chat history & reconnect

The **backend is the source of truth** for all chat history. Threads, messages, agent runs, and
every stream event are persisted in PostgreSQL (`chat_sessions`, `chat_messages`, `agent_runs`,
`chat_events`), and **each stream event is written to the database before it is emitted to the
browser**. As a result:

- **Refreshing the browser reloads the full conversation from the backend** — it does not depend on
  local data. Signing in from another device/browser shows the same server-side history.
- **Agent runs continue on the server after a client disconnect.** On reconnect the UI resumes from
  the last applied event using `afterSeq`, so it only fetches the missing tail; events are ordered by
  sequence number and de-duplicated by `runId + seq`, so a reconnect never duplicates messages.
- **`localStorage` is used only for UI convenience** — the last selected thread id and lightweight
  thread metadata. It is never the canonical chat state.

The frontend is organized as a thin composition root (`frontend/src/App.tsx`) over `screens/`
(per-screen UI), `hooks/` (auth, integrations, GitHub project setup, workspace, agent runs, thread
selection, run stream), and `services/` (`apiClient`, `threadApi`, `runApi`, `integrationApi`,
`githubApi`). See [`docs/ui-state-refactor.md`](docs/ui-state-refactor.md).

### Production credentials

For any shared or production deployment, set these environment variables (see `.env.example`):

| Variable | Purpose |
|---|---|
| `AUTH_BOOTSTRAP_USERNAME` | Primary login username (no default that ships as admin/change-me in prod). |
| `AUTH_BOOTSTRAP_PASSWORD` | **Strong, unique** password. Startup is refused if it is blank or a well-known weak value (`change-me`, `admin`, `password`, …). |
| `AUTH_BOOTSTRAP_ALLOW_INSECURE_PASSWORD` | Leave unset / `false` in production. Set to `true` only for local dev to permit a weak password (a loud warning is logged). |
| `SPRING_DATASOURCE_*` | PostgreSQL connection. |
| `OPEN_ROUTER_API_KEY` / `OPENAI_API_KEY` | LLM provider key. |
| `AUTH_JWT_SECRET_BASE64` | Optional bootstrap HMAC signing key; if set on first boot it is persisted to the database, otherwise Bouw generates and persists one automatically. |
| `RESEND_API_KEY` / `RESEND_FROM` | Resend API key and verified sender used to email login/registration verification codes. If unset, codes are logged instead of emailed (local dev only). |

### CI / validation

`.github/workflows/ci.yml` gates pull requests with:

- **Frontend** (`frontend/`): `npm ci`, `npm run typecheck`, `npm run lint`, `npm test`, `npm run build`.
- **Backend**: `mvn clean install` (unit tests + package), plus SpotBugs SAST, Trivy dependency scan, and gitleaks secret scan.
- **Docker**: `docker compose config` validation for the base stack and the sandbox override (no external LLM keys required).

Run the same checks locally:

```bash
# Frontend
cd frontend && npm ci && npm run typecheck && npm run lint && npm test && npm run build

# Backend
mvn -pl backend clean install

# Docker compose config
docker compose -f docker-compose.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.sandbox.yml config --quiet
```

## Getting started (native install)

The native CLI install is the advanced/alternative path and runs Bouw directly on your machine
(requires Java 21 and Maven, plus a reachable PostgreSQL).

```bash
git clone https://github.com/jeremyunck/bouw.git && cd bouw
./install.sh
```

`install.sh` installs the system dependencies it needs — Java 21, Maven, git, curl, Node, and
**Docker** — then builds the jars and the project-chat sandbox image. Docker is required because
project (GitHub repository) chats run in an isolated container: the repo is cloned **inside** the
container and never written to the host. Without it the dashboard reports *"the Docker CLI is
unavailable"* when you open a project. On Linux the installer pulls in `docker.io`, enables the
daemon, and adds you to the `docker` group; on macOS it installs Docker Desktop, which you must
launch so the daemon is running.

Then run the app:

```bash
bouw
```

The backend listens on `http://localhost:8080` and serves the built frontend from the same
process. Run `bouw doctor` at any time to verify Docker, the sandbox image, and the rest of the
stack.

## Commands

| Command | Description |
|---|---|
| `bouw` | Ensure the backend is running |
| `bouw onboard` | Run the interactive setup wizard |
| `bouw server run` | Run the backend in the foreground |
| `bouw server start / stop / restart / status` | Manage the background service |
| `bouw server logs` | Stream service logs |

Set `AGENT_HOME` to override the default agent home (`~/.bouw`).
Set `LLM_REASONING_EFFORT` to override the reasoning effort sent to the model.

## Prerequisites

- Java 21 and Maven
- Docker (CLI + a running daemon) — required for project/GitHub repository chats, which execute in
  an isolated container. Build the sandbox image once with
  `docker build -t bouw-agent-sandbox:latest docker/sandbox` (`install.sh` does this for you).
- An OpenAI-compatible chat endpoint
- Optional: Redis, if you enable long-term memory
- Optional: Google credentials, if you want the Google Workspace tools

## Build

```bash
mvn clean install
```

## Run the backend

```bash
mvn -pl backend spring-boot:run
```

## macOS auto-redeploy from `main`

To run this checkout as a `launchd` service and auto-update it every 30 minutes, install and start
[Docker Desktop](https://www.docker.com/products/docker-desktop/) first. The detached deploy flow
rebuilds both the backend jar and the `bouw-agent-sandbox:latest` image used for project/GitHub
repository chats.

Then run:

```bash
mvn -q -DskipTests package
./scripts/install-launchd-jobs.sh
```

The launchd updater lives at [`scripts/bouw-launchd-update.sh`](scripts/bouw-launchd-update.sh) and:

- fetches `origin/main`
- fast-forwards the checkout when there are new commits
- rebuilds the backend jar, including the frontend bundle
- rebuilds the `bouw-agent-sandbox:latest` Docker image from `docker/sandbox`
- restarts the service with `launchctl kickstart -k`

The installer writes:

- `~/Library/LaunchAgents/com.jnku.bouw.repo-server.plist`
- `~/Library/LaunchAgents/com.jnku.bouw.repo-autoupdate.plist`
- `~/.config/bouw-dev/env`

The dev launchd scripts keep runtime state, including Docker sandbox workspaces, under
`~/.local/share/bouw-dev` by default instead of creating a `sandboxes/` directory in the repo.

The updater only deploys when `origin/main` has moved. It skips if the checkout is not on
`main` or if there are tracked local changes that would make `git pull --ff-only` unsafe.

## What Bouw can do

- Chat with any OpenAI-compatible model
- Stream responses over SSE
- Read, write, and edit files inside the configured workspace
- Run shell commands in the workspace
- Search the web with the built-in `web_search` tool
- Research a topic in depth with `deep_research` — it fans out several focused web searches across
  different angles, de-duplicates the sources they cite, and returns a curated, source-backed brief
  for the model to digest and write a report from
- Turn a task it just solved into a reusable tool with `create_agent_tool` — when you ask it to
  "make that a tool", it saves the working solution as a self-contained script plus a manifest in the
  workspace's untracked `.bouw/jit-tools/` folder, loaded on the fly (no restart) for future requests
  to call directly
- Read and write Google Docs, Sheets, Calendar, and Gmail when configured
- Schedule prompts for later delivery
- Recall past conversations and optional semantic memory
- Discover repo-local skills from `skills/**/SKILL.md` and `docs/skills/**/SKILL.md`, then read a
  relevant skill before substantial workspace tasks

## Configuration

Settings live in `backend/src/main/resources/application.yml`.

| Key | Description | Default |
| --- | --- | --- |
| `llm.provider` | Active provider; must match a key under `llm.providers` | `openrouter` |
| `llm.model` | Default model when a request omits one | `openai/gpt-oss-120b` |
| `llm.providers.<name>.base-url` | OpenAI-compatible API root | _(per provider)_ |
| `llm.providers.<name>.api-key` | Optional API key sent as `Authorization: Bearer <key>` | _(blank)_ |
| `agent.api-key` | If set, `/api/agent/**` requires the `X-API-Key` header | _(blank)_ |
| `agent.request-timeout` | Per-request wall-clock budget for the agent loop | `5m` |
| `agent.tools.enabled` | Enable the built-in local tools | `true` |
| `agent.tools.workspace-root` | Workspace root for file and shell tools | `.` |
| `conversation.memory.enabled` | Enable short-term per-session conversation memory | `true` |
| `memory.enabled` | Enable Redis-backed long-term memory | `false` |
| `embedding.base-url` | OpenAI-schema embeddings API root | `https://openrouter.ai/api/v1` |
| `embedding.api-key` | Optional key for embeddings | `${OPEN_ROUTER_API_KEY:}` |
| `google.oauth-client-secrets-file` | OAuth client secrets JSON for Google user auth | _(blank)_ |
| `google.credentials-file` | Service-account key JSON for Google Workspace auth | _(blank)_ |

## Frontend

```bash
cd frontend
npm install
npm run dev
```

The frontend defaults to `http://localhost:8080` for API calls.
