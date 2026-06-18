[![Hugin banner](img/readme_banner.png)](/img/readme_banner.png)

# Hugin

Hugin is a Spring Boot + React personal assistant. It talks to any OpenAI-compatible chat
endpoint, exposes a web UI, and ships with local tools for file access, shell commands, web
search, Google Workspace, email, scheduling, and optional Redis-backed memory.

## Getting started

Clone the repo and install Hugin:

```bash
git clone https://github.com/jeremyunck/hugin.git && cd hugin
./install.sh
```

Then run the app:

```bash
hugin
```

The backend listens on `http://localhost:8080` and serves the built frontend from the same
process.

## Commands

| Command | Description |
|---|---|
| `hugin` | Ensure the backend is running |
| `hugin onboard` | Run the interactive setup wizard |
| `hugin server run` | Run the backend in the foreground |
| `hugin server start / stop / restart / status` | Manage the background service |
| `hugin server logs` | Stream service logs |

Set `AGENT_HOME` to override the default agent home (`~/.hugin`).
Set `LLM_REASONING_EFFORT` to override the reasoning effort sent to the model.

## Prerequisites

- Java 21 and Maven
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

To run this checkout as a `launchd` service and auto-update it every 30 minutes:

```bash
mvn -q -DskipTests package
./scripts/install-launchd-jobs.sh
```

The launchd updater lives at [`scripts/hugin-launchd-update.sh`](scripts/hugin-launchd-update.sh) and:

- fetches `origin/main`
- fast-forwards the checkout when there are new commits
- rebuilds the backend jar, including the frontend bundle
- restarts the service with `launchctl kickstart -k`

The installer writes:

- `~/Library/LaunchAgents/com.jnku.hugin.repo-server.plist`
- `~/Library/LaunchAgents/com.jnku.hugin.repo-autoupdate.plist`
- `~/.config/hugin-dev/env`

The dev launchd scripts keep runtime state, including Docker sandbox workspaces, under
`~/.local/share/hugin-dev` by default instead of creating a `sandboxes/` directory in the repo.

The updater only deploys when `origin/main` has moved. It skips if the checkout is not on
`main` or if there are tracked local changes that would make `git pull --ff-only` unsafe.

## What Hugin can do

- Chat with any OpenAI-compatible model
- Stream responses over SSE
- Read, write, and edit files inside the configured workspace
- Run shell commands in the workspace
- Search the web with the built-in `web_search` tool
- Read and write Google Docs, Sheets, Calendar, and Gmail when configured
- Schedule prompts for later delivery
- Recall past conversations and optional semantic memory

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
