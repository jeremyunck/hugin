# mcp-client

A Spring Boot AI agent backend that connects to [Model Context Protocol](https://modelcontextprotocol.io) (MCP) servers and lets an OpenAI-schema LLM use their tools to answer prompts. The LLM backend is pluggable — point it at a local [Ollama](https://ollama.com) instance or a hosted provider such as [OpenRouter](https://openrouter.ai) (see [LLM provider](#llm-provider)).

This is the API backend for a cloud coding agent platform. A separate front-end app talks to this backend over HTTP. The agent can clone repositories, run the agent loop (editing code, running commands), and open pull requests.

## Prerequisites

- **Java 21** and **Maven**
- An **OpenAI-compatible LLM endpoint** with a tool-calling model. Either:
  - **[Ollama](https://ollama.com)** running locally (no API key):
    ```bash
    ollama pull llama3.2
    ollama serve            # exposes http://localhost:11434
    ```
  - or a hosted provider like **[OpenRouter](https://openrouter.ai)** (recommended for cloud/CI environments — set `OPEN_ROUTER_API_KEY`).
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

Stdio servers use `command` / `args` / `env`; SSE/HTTP servers use `url` / `headers`. Servers can also be managed at runtime through the `/api/v1/servers` REST API.

### Passing secrets and environment variables

The client launches stdio servers as subprocesses and **does not inherit your shell environment** — only a fixed whitelist (`PATH`, `HOME`, etc.) is passed through. To give a server an API key or other config, put it in that server's `env` block:

```json
"ddg-mcp": {
  "command": "node",
  "args": ["/path/to/ddg-mcp/dist/server.js"],
  "env": {
    "OPEN_ROUTER_API_KEY": "sk-or-v1-..."
  }
}
```

Exporting a variable in your shell (or relying on the server's own `.env` file) will **not** work unless the server explicitly loads it — pass values through the `env` block instead. Because `mcp-servers.json` is gitignored, secrets placed here stay local.

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

## Docker

Build and run the agent server as a container. The image bundles the Spring Boot server and the Python 3 runtime for the OpenRouter web-search MCP server. No Ollama or other local LLM is required — the default configuration talks to OpenRouter over HTTPS.

### Build the image

```bash
docker build -t mcp-client .
```

### Run with `docker run`

```bash
docker run -p 8080:8080 \
  -e OPEN_ROUTER_API_KEY=sk-or-v1-... \
  mcp-client
```

| Environment variable | Description | Default |
|---|---|---|
| `OPEN_ROUTER_API_KEY` | OpenRouter API key (required for LLM and web search) | _(blank)_ |
| `AGENT_API_KEY` | Require this value as `X-API-Key` on `/api/**` requests; blank leaves endpoints open | _(blank)_ |
| `AGENT_TOOLS_ENABLED` | Enable built-in file/shell tools inside the container | `false` |
| `AGENT_TOOLS_WORKSPACE_ROOT` | Sandbox root for file/shell tools (pin to a mounted volume when enabling tools) | `.` |
| `MEMORY_ENABLED` | Enable Redis-backed long-term memory | `false` |
| `REDIS_HOST` | Redis hostname (only used when `MEMORY_ENABLED=true`) | `redis` |
| `REDIS_PORT` | Redis port | `6379` |
| `MCP_CONFIG_FILE` | Path to `mcp-servers.json` inside the container | `/app/mcp-servers.json` |
| `SEARCH_OPENROUTER_SCRIPT` | Path to `openrouter-search-mcp.py` inside the container | `/app/openrouter-search-mcp.py` |

### Run with Docker Compose

The repo includes a `docker-compose.yml`. A `memory` profile adds Redis for long-term memory.

Base setup (web search only, no long-term memory):

```bash
OPEN_ROUTER_API_KEY=sk-or-v1-... docker compose up
```

With long-term memory (adds Redis, sets `MEMORY_ENABLED=true`):

```bash
OPEN_ROUTER_API_KEY=sk-or-v1-... MEMORY_ENABLED=true docker compose --profile memory up
```

### Adding MCP servers in the container

The container starts with no `mcp-servers.json` — the OpenRouter web-search server is registered automatically at startup. To add more servers:

- **Mount a config file**: `-v /path/to/mcp-servers.json:/app/mcp-servers.json:ro`
- **Use the REST API at runtime**: POST to `/api/v1/servers` to add SSE/HTTP MCP servers without restarting.

Stdio servers that spawn subprocesses (e.g. `npx`, `uvx`) need those runtimes in the image; prefer SSE/remote MCP servers for cloud deployments.

### Health check

The container exposes a `/actuator/health` endpoint polled by the built-in `HEALTHCHECK`. You can also query it manually:

```bash
curl http://localhost:8080/actuator/health
```

### Security notes

- **Local tools are disabled by default** (`AGENT_TOOLS_ENABLED=false`). If you enable them, mount a dedicated volume and set `AGENT_TOOLS_WORKSPACE_ROOT` to its path.
- **Set `AGENT_API_KEY`** before exposing the server publicly so the API endpoints require authentication.
- The `/api/v1/servers/**` management endpoints are authenticated with the same API key.

## Usage

Send a prompt to the agent (non-streaming):

```bash
curl -X POST http://localhost:8080/api/v1/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What time is it in Tokyo?", "model": "llama3.2"}'
```

Or stream the response as Server-Sent Events:

```bash
curl -N -X POST http://localhost:8080/api/v1/agent/stream \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"prompt": "What time is it in Tokyo?", "model": "llama3.2"}'
```

`model` is optional and falls back to `llm.model` from configuration. Use a model name valid for the active provider (e.g. `llama3.2` for Ollama, `deepseek/deepseek-chat` for OpenRouter).

### Cloud agent endpoints

Create and run a cloud agent (clones a repo, runs the agent loop, opens a PR):

```bash
curl -N -X POST http://localhost:8080/api/v1/agents \
  -H "Content-Type: application/json" \
  -d '{"repoUrl": "https://github.com/user/repo", "task": "Add error handling", "branch": "main", "model": "deepseek/deepseek-chat"}'
```

Manage cloud agents:

```bash
curl http://localhost:8080/api/v1/agents                     # list agents + status
curl http://localhost:8080/api/v1/agents/{id}                # get agent metadata
curl -X DELETE http://localhost:8080/api/v1/agents/{id}      # stop + delete agent
```

### MCP server management

```bash
curl http://localhost:8080/api/v1/servers                    # list servers + connection status
curl http://localhost:8080/api/v1/servers/time/tools          # list a server's tools
curl -X POST http://localhost:8080/api/v1/servers/time/reconnect
```

## API documentation

OpenAPI documentation is available when the server is running:

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI JSON**: http://localhost:8080/v3/api-docs

## LLM provider

The agent talks to any OpenAI-schema `/chat/completions` endpoint. Providers are declared under `llm.providers` in `application.yml`, and `llm.provider` selects the active one:

```yaml
llm:
  provider: openrouter              # or "ollama" for a local instance
  model: openai/gpt-oss-120b        # default model for the active provider
  providers:
    ollama:
      base-url: http://localhost:11434/v1   # no api-key → no auth header sent
    openrouter:
      base-url: https://openrouter.ai/api/v1
      api-key: ${OPEN_ROUTER_API_KEY:}       # sent as Authorization: Bearer <key>
```

- **Ollama** has no `api-key`, so requests are sent without an `Authorization` header.
- **OpenRouter** authenticates with an `Authorization: Bearer <api-key>` header ([OpenRouter auth docs](https://openrouter.ai/docs/api/reference/authentication)). Provide the key via the `OPEN_ROUTER_API_KEY` environment variable and set `llm.provider: openrouter` (also pick an OpenRouter `model`, e.g. `openai/gpt-oss-120b`).

To add another OpenAI-compatible provider, add an entry under `llm.providers` with its `base-url` and (optionally) `api-key`, then point `llm.provider` at it.

## Web search

A web search MCP server is registered automatically at startup. Configure it via `search.provider` in `application.yml`:

```yaml
search:
  provider: openrouter   # or: duckduckgo
  openrouter-script: ../openrouter-search-mcp.py
```

| Provider | How it works | Requirements |
| --- | --- | --- |
| `openrouter` *(default)* | Calls `perplexity/sonar` via OpenRouter for real-time web search. Reliable from cloud/server IPs. | `OPEN_ROUTER_API_KEY`, `python3` with `mcp` package (`pip install mcp`) |
| `duckduckgo` | Launches `duckduckgo-mcp-server` via `uvx`. No API key needed, but DuckDuckGo applies bot detection that blocks most cloud IP ranges. Works well on local developer machines. | `uvx` |

The `openrouter-search-mcp.py` script is in the repo root. The `openrouter-script` path is resolved relative to the working directory of `mcp-integration` at startup — the default `../openrouter-search-mcp.py` works when running with `mvn -pl mcp-integration spring-boot:run` from the repo root.

If a `web-search` entry already exists in `mcp-servers.json`, that entry takes precedence and auto-configuration is skipped.

## Long-term memory

The agent can keep a **Redis-backed long-term memory** of past conversations using text embeddings. It is **disabled by default**. When enabled, every finished exchange (the prompt and the agent's final answer) is embedded and stored in Redis; on each new request the most similar past memories are recalled and injected into the prompt as extra context, so the agent can remember things across requests and restarts.

Similarity search runs in-process (cosine similarity over the stored vectors), so it works against plain Redis — no RediSearch or vector modules required.

### Setup

1. **Start Redis** (any 6+ instance). For a quick local one:

   ```bash
   docker run -p 6379:6379 redis:7
   ```

2. **Provide an embedding endpoint.** Embeddings are generated through an OpenAI-schema `POST {base-url}/embeddings` call. The defaults target **OpenRouter**, so set your key:

   ```bash
   export OPEN_ROUTER_API_KEY=sk-or-v1-...
   ```

   (The embedding endpoint is configured independently of the chat LLM — you can embed via OpenRouter while chatting against a local Ollama model.)

3. **Enable memory** when starting the server:

   ```bash
   MEMORY_ENABLED=true mvn -pl mcp-integration spring-boot:run
   ```

   Or set `memory.enabled: true` in `application.yml`.

### Configuration

```yaml
memory:
  enabled: ${MEMORY_ENABLED:false}   # master switch
  key-prefix: agent:memory           # Redis key prefix (hash key is "<prefix>:records")
  top-k: 3                           # how many past memories to recall into the prompt
  min-score: 0.75                    # min cosine similarity (0..1) to recall; 0 disables the threshold
  max-entries: 1000                  # cap on stored memories; oldest are evicted past this

embedding:
  base-url: ${EMBEDDING_BASE_URL:https://openrouter.ai/api/v1}
  api-key: ${OPEN_ROUTER_API_KEY:}    # sent as Authorization: Bearer <key> when set
  model: ${EMBEDDING_MODEL:openai/text-embedding-3-small}   # model used specifically for embeddings

spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
```

To use a different embedding model, set `embedding.model` (or the `EMBEDDING_MODEL` env var) — for example a local Ollama embedding model by also pointing `embedding.base-url` at `http://localhost:11434/v1`. Embedding and store failures are non-fatal: if Redis or the embedding endpoint is unavailable the agent logs a warning and continues without memory.

## Configuration

Settings live in `mcp-integration/src/main/resources/application.yml`:

| Key | Description | Default |
| --- | --- | --- |
| `llm.provider` | Active provider; must match a key under `llm.providers` | `ollama` |
| `llm.model` | Default model when a request omits one (must support tool calling) | `qwen-coder-3:latest` |
| `llm.providers.<name>.base-url` | OpenAI-compatible API root (the part before `/chat/completions`) | _(per provider)_ |
| `llm.providers.<name>.api-key` | Optional; when set, sent as `Authorization: Bearer <key>` | _(blank)_ |
| `mcp.config-file` | Path to the MCP servers JSON (supports `~/`) | `./mcp-servers.json` |
| `agent.api-key` | If set, all `/api/**` endpoints require the `X-API-Key` header; if blank, endpoints are open | _(blank)_ |
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
| `search.provider` | Web search MCP provider registered at startup: `openrouter` or `duckduckgo` (see [Web search](#web-search)) | `openrouter` |
| `search.openrouter-script` | Path to `openrouter-search-mcp.py` (used when `provider=openrouter`) | `../openrouter-search-mcp.py` |
