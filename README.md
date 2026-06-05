

![Hugin banner](img/readme_banner.png)

# Hugin

Hugin is an AI personal assistant built on Spring Boot that connects to [Model Context Protocol](https://modelcontextprotocol.io) (MCP) servers and lets an OpenAI-schema LLM use their tools to answer prompts. The LLM backend is pluggable — point it at any hosted provider such as [OpenRouter](https://openrouter.ai) (see [LLM provider](#llm-provider)).

## Getting started

Clone the repo and install the Hugin CLI:

```bash
git clone https://github.com/jeremyunck/hugin.git && cd hugin
./install.sh
```

Then run the interactive setup:

```bash
hugin onboard
```

Onboarding will:
- Install Java 21 (Temurin), git, and Maven if missing
- Prompt for your **OpenRouter API key** (required — used for LLM + web search)
- Prompt for a **Redis host** for long-term memory (leave blank to skip)
- Build the fat jars, create `~/.hugin/`, and register a **systemd service** that starts on boot

From then on, one command ensures the server is running:

```bash
hugin        # starts the service if needed and waits for health
```

The agent server is always available on **`:8080`** for direct API access too:

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "What can you do?",
    "decision": "llama3.2",
    "complex": "openai/gpt-oss-120b",
    "simple": "openai/gpt-oss-20b"
  }'
```

## Commands

| Command | Description |
|---|---|
| `hugin` | Ensure server is running |
| `hugin onboard` | Run the interactive setup wizard |
| `hugin server run` | Run the server in the foreground (no systemd) |
| `hugin server start / stop / restart / status` | Manage the background service |
| `hugin server logs` | Stream service logs (`journalctl -f`) |

Set `AGENT_HOME` to override the default install location (`~/.hugin`).
Set `LLM_REASONING_EFFORT` to override the default reasoning effort sent to the model (`medium` by default).

## Prerequisites

- **Java 21** and **Maven**
- An **OpenAI-compatible LLM endpoint** with a tool-calling model. [OpenRouter](https://openrouter.ai) is recommended — set `OPEN_ROUTER_API_KEY`.
- If you want to tune reasoning depth without editing the repo, set `LLM_REASONING_EFFORT` in `~/.hugin/hugin.env` or your shell environment. The default is `medium`.
- Runtimes for the MCP servers you configure. The default `mcp-servers.json` uses:
  - `uvx` ([uv](https://docs.astral.sh/uv/)) for the time server
  - `python3` for the web search server (when `search.provider=openrouter`, the default)
- **(Optional) Redis** — only needed if you enable [long-term memory](#long-term-memory). Any Redis 6+ works; no modules required.

## Configure MCP servers

Servers are declared in `mcp-servers.json` (repo root) using the standard Claude Desktop format. This file is **gitignored** so your local server list stays private — create it by copying the committed example:

```bash
cp mcp-servers.example.json mcp-servers.json
```

`mcp-servers.example.json`:

```json
{
  "mcpServers": {
    "filesystem": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"],
      "env": {}
    },
    "time": {
      "command": "uvx",
      "args": ["mcp-server-time"],
      "env": {}
    }
  }
}
```

Stdio servers use `command` / `args` / `env`; SSE/HTTP servers use `url` / `headers`. Servers can also be managed at runtime through the `/api/servers` REST API.

Stdio servers do not inherit your shell environment — pass API keys and other config through each server's `env` block. `mcp-servers.json` is gitignored so secrets placed there stay local.

## Build

```bash
mvn clean install
```

## Run

Start the agent **server**:

```bash
mvn -pl mcp-integration spring-boot:run
```

The server starts on **port 8080**. `agent-core` is the shared logic library.

## Usage

Send a prompt to the agent (non-streaming):

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{
    "prompt": "What time is it in Tokyo?",
    "decision": "llama3.2",
    "complex": "openai/gpt-oss-120b",
    "simple": "openai/gpt-oss-20b"
  }'
```

Or stream the response as Server-Sent Events:

```bash
curl -N -X POST http://localhost:8080/api/agent/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{
    "prompt": "What time is it in Tokyo?",
    "decision": "llama3.2",
    "complex": "openai/gpt-oss-120b",
    "simple": "openai/gpt-oss-20b"
  }'
```

`decision` is asked to classify the task as simple or complex, then the agent routes to
`simple` or `complex` accordingly. `model` is still accepted as a legacy fallback for older
callers, and if the routing fields are omitted the agent falls back to the configured default
`llm.model`.

Manage MCP servers at runtime:

```bash
curl http://localhost:8080/api/servers                 # list servers + connection status
curl http://localhost:8080/api/servers/time/tools      # list a server's tools
curl -X POST http://localhost:8080/api/servers/time/reconnect
```

## LLM provider

The agent talks to any OpenAI-schema `/chat/completions` endpoint. Providers are declared under `llm.providers` in `application.yml`, and `llm.provider` selects the active one:

```yaml
llm:
  provider: openrouter
  model: openai/gpt-oss-120b        # default model for the active provider
  providers:
    openrouter:
      base-url: https://openrouter.ai/api/v1
      api-key: ${OPEN_ROUTER_API_KEY:}       # sent as Authorization: Bearer <key>
```

**OpenRouter** authenticates with an `Authorization: Bearer <api-key>` header ([OpenRouter auth docs](https://openrouter.ai/docs/api/reference/authentication)). Provide the key via the `OPEN_ROUTER_API_KEY` environment variable.

To add another OpenAI-compatible provider, add an entry under `llm.providers` with its `base-url` and (optionally) `api-key`, then point `llm.provider` at it.

## Web search

Web search is a built-in local tool (`web_search`) that calls `perplexity/sonar` via OpenRouter for real-time results. It activates automatically when `OPEN_ROUTER_API_KEY` is set and `agent.tools.enabled` is `true` (the default). No extra runtime dependencies are required.

## Google Workspace

Hugin can create, read, and edit Google Docs and Sheets, create Calendar events, and search/read/send Gmail through built-in local tools backed by the official Google API Java client libraries.

`google_docs_create` accepts Markdown for its `text` field and renders it into Google Docs structure such as headings, bullets, block quotes, code blocks, and formatted inline text instead of inserting literal Markdown markers.

Authentication can use either:

- **OAuth with a Google user account** for a personal install, which is the preferred path now
- **A Google service account** for Workspace/domain-wide delegation setups

OAuth setup:

1. In a Google Cloud project, enable the Google **Docs**, **Sheets**, **Drive**, **Calendar**, and **Gmail** APIs.
2. Create an OAuth client for a desktop app and download the JSON.
3. Set `GOOGLE_OAUTH_CLIENT_SECRETS_FILE` to that JSON path (or `google.oauth-client-secrets-file` in `application.yml`).
4. On first use, Hugin opens the browser for consent and caches refresh tokens in `GOOGLE_OAUTH_TOKEN_DIR` (default `~/.hugin/google-oauth`).
5. Newly created docs/sheets are created as that Google user, so `share_with` is only needed if you want to share them with someone else.

Service account setup is still supported for Workspace/domain-wide delegation:

1. In a Google Cloud project, enable the Google **Docs**, **Sheets**, **Drive**, **Calendar**, and **Gmail** APIs.
2. Create a service account and download its JSON key.
3. Set `GOOGLE_APPLICATION_CREDENTIALS` to the key's path (or `google.credentials-file` in `application.yml`).
4. Share the docs/sheets/email data you want Hugin to access with the service account's email.

Gmail support adds three tools:

- `google_gmail_search` to search the mailbox with Gmail query syntax and return matching messages
- `google_gmail_read` to read a specific message in detail
- `google_gmail_send` to send a new message or reply to an existing thread

Common Gmail workflows:

- Inbox triage: search unread mail with `google_gmail_search`, then read the most important items with `google_gmail_read`.
- Reply workflow: read a message, decide on the response, then call `google_gmail_send` with `reply_to_message_id` and the response body.
- Follow-up workflow: search for older unresolved threads with queries like `is:unread older_than:7d`, summarize the open items, and send replies from the same tool.

When no credentials are configured the tools report themselves as unavailable rather than failing startup. Workspace domains can still use domain-wide delegation via `GOOGLE_IMPERSONATE_USER` when using a service account. See [`docs/skills/google-docs-sheets`](docs/skills/google-docs-sheets/SKILL.md) and [`docs/skills/google-gmail`](docs/skills/google-gmail/SKILL.md) for usage details and the [Configuration](#configuration) table for the `google.*` settings.

## Long-term memory

The agent can keep a **Redis-backed long-term memory** of past conversations using text embeddings. It is **disabled by default**. When enabled, every finished exchange (the prompt and the agent's final answer) is embedded and stored in Redis; on each new request the most similar past memories are recalled and injected into the prompt as extra context, so the agent can remember things across requests and restarts.

Similarity search runs in-process (cosine similarity over the stored vectors), so it works against plain Redis — no RediSearch or vector modules required.

To enable: start a Redis instance, set `OPEN_ROUTER_API_KEY` (used for embeddings), then start the server with `MEMORY_ENABLED=true`. See the [Configuration](#configuration) table for all tunables (`memory.*`, `embedding.*`, `spring.data.redis.*`). Embedding and store failures are non-fatal — the agent logs a warning and continues without memory.

## Configuration

Settings live in `mcp-integration/src/main/resources/application.yml`:

| Key | Description | Default |
| --- | --- | --- |
| `llm.provider` | Active provider; must match a key under `llm.providers` | `openrouter` |
| `llm.model` | Default model when a request omits one (must support tool calling) | `openai/gpt-oss-120b` |
| `llm.providers.<name>.base-url` | OpenAI-compatible API root (the part before `/chat/completions`) | _(per provider)_ |
| `llm.providers.<name>.api-key` | Optional; when set, sent as `Authorization: Bearer <key>` | _(blank)_ |
| `mcp.config-file` | Path to the MCP servers JSON (supports `~/`) | `./mcp-servers.json` |
| `agent.api-key` | If set, `/api/agent/**` requires the `X-API-Key` header; if blank, those endpoints are open | _(blank)_ |
| `agent.request-timeout` | Per-request wall-clock budget for the agent loop | `5m` |
| `google.oauth-client-secrets-file` | Path to a Google OAuth client-secrets JSON enabling browser-based Google user auth | _(blank)_ |
| `google.oauth-token-dir` | Directory where OAuth refresh tokens are cached | `~/.hugin/google-oauth` |
| `google.oauth-local-server-port` | Local loopback port used for the initial OAuth callback | `8765` |
| `google.credentials-file` | Path to a Google service-account JSON key enabling the Google Workspace tools | `${GOOGLE_APPLICATION_CREDENTIALS:}` |
| `google.application-name` | Application name reported to the Google APIs | `Hugin` |
| `google.impersonate-user` | Optional user email to impersonate via domain-wide delegation | `${GOOGLE_IMPERSONATE_USER:}` |
| `google.default-share-with` | Optional email that newly created docs/sheets are auto-shared with | `${GOOGLE_DEFAULT_SHARE_WITH:}` |
| `memory.enabled` | Enable Redis-backed long-term memory (see [Long-term memory](#long-term-memory)) | `false` |
| `memory.key-prefix` | Redis key prefix for stored memory records | `agent:memory` |
| `memory.top-k` | Number of most-similar past memories recalled into the prompt | `3` |
| `memory.min-score` | Minimum cosine similarity (0..1) to recall a memory; `0` disables the threshold | `0.75` |
| `memory.max-entries` | Cap on stored memories; oldest are evicted past this | `1000` |
| `embedding.base-url` | OpenAI-schema embeddings API root (before `/embeddings`) | `https://openrouter.ai/api/v1` |
| `embedding.api-key` | Optional; when set, sent as `Authorization: Bearer <key>` | `${OPEN_ROUTER_API_KEY:}` |
| `embedding.model` | Model used specifically for embedding text | `openai/text-embedding-3-small` |
| `spring.data.redis.host` / `.port` | Redis connection (only used when `memory.enabled`) | `localhost` / `6379` |
