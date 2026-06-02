

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

From then on, one command opens the chat:

```bash
hugin        # starts the service if needed, waits for health, opens terminal
```

The agent server is always available on **`:8080`** for direct API access too:

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What can you do?"}'
```

## Commands

| Command | Description |
|---|---|
| `hugin` | Ensure server is running, then open terminal chat |
| `hugin onboard` | Run the interactive setup wizard |
| `hugin server run` | Run the server in the foreground (no systemd) |
| `hugin server start / stop / restart / status` | Manage the background service |
| `hugin server logs` | Stream service logs (`journalctl -f`) |
| `hugin terminal` | Launch the terminal client directly |

Set `AGENT_HOME` to override the default install location (`~/.hugin`).

## Prerequisites

- **Java 21** and **Maven**
- An **OpenAI-compatible LLM endpoint** with a tool-calling model. [OpenRouter](https://openrouter.ai) is recommended — set `OPEN_ROUTER_API_KEY`.
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

The server starts on **port 8080**. `agent-core` and `mcp-client` are libraries.

## Terminal front-end

`agent-terminal` is an interactive terminal client — think Claude Code. With the server running, start it in another shell:

```bash
mvn -pl agent-terminal spring-boot:run
```

Type a prompt and the answer **streams back token-by-token** in real time; tool calls the agent makes are shown inline. Built-in commands:

- `/help` — show help
- `/model [name]` — show or change the model used for new prompts
- `/exit` (also `/quit`, `exit`, `quit`, or Ctrl-D) — quit

Configure it via the `terminal` block in `agent-terminal/src/main/resources/application.yml` (or environment variables):

| Key | Env var | Description | Default |
| --- | --- | --- | --- |
| `terminal.server-url` | `AGENT_SERVER_URL` | Base URL of the running server | `http://localhost:8080` |
| `terminal.api-key` | `AGENT_API_KEY` | Sent as `X-API-Key`; needed only if the server sets `agent.api-key` | _(blank)_ |
| `terminal.model` | `AGENT_MODEL` | Default model; blank lets the server pick `llm.model` | _(blank)_ |

## Usage

Send a prompt to the agent (non-streaming):

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What time is it in Tokyo?"}'
```

Or stream the response as Server-Sent Events (this is what the terminal uses):

```bash
curl -N -X POST http://localhost:8080/api/agent/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"prompt": "What time is it in Tokyo?"}'
```

`model` is optional and falls back to `llm.model` from configuration.

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
| `memory.enabled` | Enable Redis-backed long-term memory (see [Long-term memory](#long-term-memory)) | `false` |
| `memory.key-prefix` | Redis key prefix for stored memory records | `agent:memory` |
| `memory.top-k` | Number of most-similar past memories recalled into the prompt | `3` |
| `memory.min-score` | Minimum cosine similarity (0..1) to recall a memory; `0` disables the threshold | `0.75` |
| `memory.max-entries` | Cap on stored memories; oldest are evicted past this | `1000` |
| `embedding.base-url` | OpenAI-schema embeddings API root (before `/embeddings`) | `https://openrouter.ai/api/v1` |
| `embedding.api-key` | Optional; when set, sent as `Authorization: Bearer <key>` | `${OPEN_ROUTER_API_KEY:}` |
| `embedding.model` | Model used specifically for embedding text | `openai/text-embedding-3-small` |
| `spring.data.redis.host` / `.port` | Redis connection (only used when `memory.enabled`) | `localhost` / `6379` |
