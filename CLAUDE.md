# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

Maven multi-module project (Java 21, Spring Boot 3.5.x). Run from the repo root.

```bash
./install.sh   # fresh install

hugin update   # refresh code from origin/main
```

Two modules have a main class: `mcp-integration` (`McpClientApplication`, the agent **server** on port 8080) and `agent-terminal` (`AgentTerminalApplication`, the terminal **client**). `agent-core` and `mcp-client` are libraries. The terminal connects to a running server, so start `mcp-integration` first.

### Runtime prerequisites
- **An OpenAI-schema LLM endpoint** with a tool-calling model, selected via `llm.provider` in `mcp-integration/src/main/resources/application.yml`. Default is local **Ollama** at `http://localhost:11434/v1` (e.g. `llama3.2`, `mistral-nemo`); the `openrouter` provider is also configured and uses an API key (`OPEN_ROUTER_API_KEY`).
- **MCP server runtimes**: the default `mcp-servers.json` launches stdio servers via `npx` (filesystem) and `uvx` (time), so Node/npm and uv must be on PATH for those to connect.
- **Redis** — only when long-term memory is enabled (`memory.enabled=true`); connection from `spring.data.redis.*` (`REDIS_HOST`/`REDIS_PORT`). Not needed for the default config or for short-term conversation memory (which is in-process).
- **An embeddings endpoint** — only when `memory.enabled=true`; an OpenAI-schema `/embeddings` API configured under `embedding.*` (defaults to OpenRouter, needs `OPEN_ROUTER_API_KEY`).

## Architecture

Four modules with a deliberate dependency rule: **`agent-core` must never depend on the MCP SDK** (and, more generally, on any transport/storage implementation). It defines several **SPI interfaces** — `McpToolProvider`, `MemoryStore`, `ConversationStore` — that are implemented in `mcp-integration`, so the agent logic stays decoupled from MCP, Redis, etc.

- **`agent-core`** — the LLM agent loop, the `OpenAiClient` (a generic OpenAI-schema chat-completions HTTP client; active provider, base URL, optional API key from `LlmProperties`), the **built-in local tools** (`tool/` package, see below), the `EmbeddingClient`, and the two memory orchestration services (`MemoryService`, `ConversationMemoryService`). Pure logic; depends only on Spring base + Jackson. Talks to MCP only through `McpToolProvider`, and to persistence only through the `MemoryStore` / `ConversationStore` interfaces — none of which it implements (except the in-process `InMemoryConversationStore`).
- **`mcp-client`** — the MCP server registry. Owns the full lifecycle (connect/disconnect/reconnect) of MCP servers via the MCP Java SDK, plus a `/api/servers` CRUD REST API. No main class.
- **`mcp-integration`** — the runnable Spring Boot **server**. Wires the other two together. `McpToolProviderImpl` is **the only class that imports both MCP SDK types and agent-core types** — it adapts `McpServerRegistryService` to the `McpToolProvider` interface. Also provides `RedisMemoryStore` (the Redis-backed `MemoryStore` impl), owns security config, and exposes the agent over HTTP (`/api/agent/chat` and the streaming `/api/agent/stream`).
- **`agent-terminal`** — the interactive terminal **front-end** (`AgentTerminalApplication`). A console-only Spring Boot app (no web server) that POSTs prompts to the server's `/api/agent/stream` SSE endpoint and renders the answer token-by-token, Claude-Code style. It is a thin HTTP client: depends on `agent-core` only to reuse the `AgentRequest` model, never on the MCP SDK or `mcp-integration`. Config under the `terminal` prefix (`server-url`, `api-key`, `model`).

This boundary is enforced by the POMs (agent-core has no MCP dependency) and exists so the agent logic stays decoupled from the MCP transport implementation. When adding agent features, keep MCP-specific types out of `agent-core`; add to the `McpToolProvider` interface and implement in `mcp-integration` instead.

### Agent loop (`AgentService.chat`)
1. Flattens **built-in local tools first, then** all tools from all connected MCP servers into one OpenAI-format tool list, building a `toolName → serverName` reverse map. Local tools take precedence on name collision — a colliding MCP tool is shadowed (logged and skipped).
2. Builds the message list: optional tool-use system prompt, optional recalled long-term memories (when `memory.enabled`), the replayed short-term conversation history for this `sessionId` (when present), then the user prompt.
3. Sends to the configured LLM (`OpenAiClient`) advertising those tools. The model name comes from the request, falling back to `llm.model`.
4. If the model returns `tool_calls`, executes each — local tools run in-process via `LocalToolRegistry`, otherwise routed to the owning server via `McpToolProvider.callTool` — appends results as `tool` messages, and repeats.
5. On the final answer, persists the exchange to long-term memory (`MemoryService.remember`) and short-term conversation memory (`ConversationMemoryService.record`).
6. Loop is bounded by `agent.max-iterations` (default 30) and a wall-clock `agent.request-timeout` (default 5m).

Note: tool calls are handled whenever `tool_calls` are present **regardless of `finish_reason`** — some models set `stop` or null even with tool calls present. Preserve this behavior.

### Streaming (`AgentService.chatStream` / `/api/agent/stream`)
`chatStream` runs the exact same loop as `chat` but calls `OpenAiClient.chatStream` (`stream: true`, parses the OpenAI SSE response, reassembling content and tool-call argument fragments) and reports progress through an `AgentStreamListener` (`onContent` per text token, `onToolCall`/`onToolResult` per tool). The server's `/api/agent/stream` endpoint runs the loop on a background thread (`agentStreamExecutor`) and re-emits these as SSE events — `token` `{"text"}`, `tool` `{"name","args"}`, `tool_result` `{"name","result"}`, `done`, `error` — which `agent-terminal` consumes. Keep the streaming and non-streaming loops behaviourally identical (`runLoop` is the shared implementation).

### MCP server registry (`McpServerRegistryService`)
- Reads/writes `mcp-servers.json` in **Claude Desktop format** (`mcpServers` map; stdio = `command`/`args`/`env`, SSE = `url`/`headers`). `resolvedType()` picks STDIO vs SSE based on presence of `url`.
- Connects to all configured servers on `@PostConstruct`; closes them on `@PreDestroy`. Connection failures are recorded in `connectionErrors` (not fatal) and surfaced via `ServerInfo`.
- Spring AI's MCP auto-configuration is **deliberately disabled** (`spring.ai.mcp.client.enabled=false`) so this service owns the entire lifecycle. Don't re-enable it.
- Config file path comes from the `mcp` config prefix; supports `~/`-relative paths.

### Built-in local tools (`agent-core/.../tool/`)
- In-process tools the agent can call directly, with **no MCP-SDK dependency** so they live in `agent-core`: `read_file`, `write_file`, `edit_file`, `list_files`, `find_files`, `find_path`, `grep_search`, `run_bash`. Each implements `LocalTool` (discovered as Spring beans) and is collected by `LocalToolRegistry`. `find_path` is the forgiving "locate" tool — it finds files and directories by name or path fragment (case-insensitive, fuzzy) even when the exact path doesn't exist, so the agent can resolve approximate paths the user gives (e.g. "look for the folder /code/hugin/hugin"). `find_files` (glob) takes an optional `type` (`file`/`dir`/`any`) to match directories too.
- Gated by `agent.tools.enabled` (master switch). When false the registry is empty — no local tools advertised or executable.
- **All file/shell access is confined to a single workspace root** by `Workspace`: paths resolve relative to the root or absolute, but the symlink-resolved path must stay inside the root (blocks `../` traversal and symlink escapes). The root is also the working dir for `run_bash`. Configure via `agent.tools.workspace-root` (relative/`~` are NOT expanded — pin an absolute path). These tools grant filesystem + shell access; scope or disable them when that's a concern.
- The tool's `execute(args, ctx)` receives a `ToolContext` carrying the request's `Workspace` **and `sessionId`** (the origin, e.g. `discord-channel-123`). Tools that need to route something back to the caller use `ctx.sessionId()` as the reply-to target — see the scheduling tools below.

### Self-scheduling (`agent-core/.../scheduler/`)
The agent can schedule a prompt to run later, on behalf of (and delivered back to) whoever asked — e.g. "every weekday at 9am CST, summarise Reddit stock news". This is built from transport-agnostic pieces in `agent-core` plus a delivery bridge in `mcp-integration`/`agent-discord`:
- **Tools** (built-in `LocalTool`s, gated by `agent.scheduler.enabled`): `schedule_prompt` (one-shot `at` ISO datetime **or** recurring `cron`, with `timezone`), `list_scheduled_prompts`, `cancel_scheduled_prompt`. They capture `ctx.sessionId()` as the **delivery target** so the scheduled answer returns to the originating channel/DM/caller. List & cancel are scoped to the caller's own target.
- **`ScheduledPromptService`** owns the lifecycle: registers triggers on a `TaskScheduler`, and when one fires runs the prompt through `AgentService.chat` under the same `sessionId` (so conversation continuity is preserved), then hands the result to a `ScheduledResultDelivery`. It depends on `AgentService` **lazily** (`ObjectProvider`) to break the cycle — the scheduling tools are themselves part of the agent's tool set. One-shot schedules remove themselves after firing (and run once on startup if they were due while offline); recurring ones persist.
- **SPIs**: `ScheduledPromptStore` (persistence; default `JsonFileScheduledPromptStore` writes `agent.scheduler.store-file` so schedules survive a restart) and `ScheduledResultDelivery` (delivery; transport-agnostic, so `agent-core` never depends on a channel). When no delivery bean is present the result is logged only.
- **Delivery** (the bridge, because the server cannot push to a chat client — clients connect to it): `mcp-integration`'s `OutboxResultDelivery` fans each finished result out over SSE at `GET /api/agent/deliveries` (under `/api/agent/**`, so same auth). `agent-discord`'s `DeliverySubscriber` holds that stream open and calls `DiscordBotService.deliverScheduledResult`, which posts to the `discord-channel-<id>` / `discord-dm-<userId>` parsed from the target. Keep this the only place that maps a target to a concrete channel.

### Memory (two independent layers)
- **Short-term, per-session conversation memory** (`ConversationMemoryService` + `ConversationStore`): replays the recent verbatim turns of a single session back into the model so it remembers the conversation. Keyed by an opaque `sessionId` on `AgentRequest` (requests without one stay stateless; `agent-terminal` generates a UUID per session). Sliding window of `conversation.memory.max-messages`. Only the user prompt + final answer are stored per turn — tool-call scaffolding is dropped so the trimmed history is always a valid transcript. **Enabled by default**, in-process via `InMemoryConversationStore` (no external dependency). `ConversationStore.append` must be atomic to avoid losing turns under concurrent same-session requests.
- **Long-term semantic memory** (`MemoryService` + `EmbeddingClient` + `MemoryStore`): embeds each finished exchange and stores it; on later requests recalls the top-k most cosine-similar past memories and injects them into the prompt. **Disabled by default** (`memory.enabled`); requires Redis + an embeddings endpoint. `RedisMemoryStore` keeps records as JSON in one Redis hash and ranks in-process (works against vanilla Redis, no RediSearch). Both recall and store are **best-effort** — failures are logged and swallowed so the loop keeps working.

## Configuration (`application.yml`)
- `llm.provider` — active LLM provider; must match a key under `llm.providers` (`ollama`, `openrouter`, …).
- `llm.model` — default model used when a request omits one.
- `llm.providers.<name>.base-url` / `.api-key` — OpenAI-compatible API root (before `/chat/completions`) and optional key. When `api-key` is set it is sent as `Authorization: Bearer <key>` (Ollama omits it; OpenRouter requires it).
- `mcp.config-file` — path to the MCP servers JSON (default `./mcp-servers.json`).
- `agent.api-key` — if set, `/api/agent/**` requires the `X-API-Key` header; if blank, those endpoints are open (relies on network-level security). The `/api/servers/**` CRUD endpoints are currently **unauthenticated** regardless.
- `agent.request-timeout` — per-request wall-clock budget for the agent loop.
- `agent.tools.*` — built-in local tools: `enabled` (master switch), `workspace-root` (sandbox + shell working dir), `bash-timeout`, `max-output-chars` (per-result cap), `shell` (binary `run_bash` invokes; blank auto-detects `$SHELL`, falling back to `/bin/sh`), `login-shell` (default true; runs commands through a login shell `-l` so the user's profile is sourced — this is what makes Homebrew tools like `brew` on PATH resolvable).
- `agent.scheduler.*` — self-scheduling: `enabled` (default true, `SCHEDULER_ENABLED`; master switch for the `schedule_prompt`/`list_scheduled_prompts`/`cancel_scheduled_prompt` tools and the runner), `store-file` (JSON persistence path, `~/` expanded), `max-per-target` (cap on active schedules per origin), `default-zone` (IANA tz used when a request omits one). Scheduled results are delivered over the `GET /api/agent/deliveries` SSE stream.
- `conversation.memory.*` — short-term session memory: `enabled` (default true, `CONVERSATION_MEMORY_ENABLED`), `max-messages` (window), `ttl` (idle eviction).
- `memory.*` — long-term semantic memory: `enabled` (default false, `MEMORY_ENABLED`), `key-prefix`, `top-k`, `min-score` (cosine threshold), `max-entries` (eviction cap). Requires `spring.data.redis.*` (`REDIS_HOST`/`REDIS_PORT`).
- `embedding.*` — embeddings endpoint used by long-term memory: `base-url` (`POST {base-url}/embeddings`), `api-key`, `model` (independent of `llm.model`).

## CI
`.github/workflows/openrouter-review.yml` runs an automated OpenRouter PR review (via the `jeremyunck/openrouter-review` action) on every PR. There is no other CI build/test pipeline.
