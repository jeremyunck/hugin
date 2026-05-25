# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

Maven multi-module project (Java 21, Spring Boot 3.5.x). Run from the repo root.

```bash
mvn clean install            # build all modules + run tests
mvn -pl agent-core test      # test a single module
mvn -pl mcp-integration spring-boot:run   # run the agent server (port 8080)
mvn -pl agent-terminal spring-boot:run    # run the interactive terminal front-end

# run a single test class / method
mvn -pl agent-core test -Dtest=AgentServiceTest
mvn -pl agent-core test -Dtest=AgentServiceTest#methodName
```

Two modules have a main class: `mcp-integration` (`McpClientApplication`, the agent **server** on port 8080) and `agent-terminal` (`AgentTerminalApplication`, the terminal **client**). `agent-core` and `mcp-client` are libraries. The terminal connects to a running server, so start `mcp-integration` first.

### Runtime prerequisites
- **An OpenAI-schema LLM endpoint** with a tool-calling model, selected via `llm.provider` in `mcp-integration/src/main/resources/application.yml`. Default is local **Ollama** at `http://localhost:11434/v1` (e.g. `llama3.2`, `mistral-nemo`); the `openrouter` provider is also configured and uses an API key (`OPENROUTER_API_KEY`).
- **MCP server runtimes**: the default `mcp-servers.json` launches stdio servers via `npx` (filesystem) and `uvx` (time), so Node/npm and uv must be on PATH for those to connect.

## Architecture

Four modules with a deliberate dependency rule: **`agent-core` must never depend on the MCP SDK.**

- **`agent-core`** — the LLM agent loop and the `OpenAiClient` (a generic OpenAI-schema chat-completions HTTP client; the active provider — Ollama, OpenRouter, … — and its base URL / optional API key come from `LlmProperties`). Pure logic; depends only on Spring base + Jackson. It talks to MCP only through the `McpToolProvider` interface (`agent-core/.../McpToolProvider.java`), which it does *not* implement.
- **`mcp-client`** — the MCP server registry. Owns the full lifecycle (connect/disconnect/reconnect) of MCP servers via the MCP Java SDK, plus a `/api/servers` CRUD REST API. No main class.
- **`mcp-integration`** — the runnable Spring Boot **server**. Wires the other two together. `McpToolProviderImpl` is **the only class that imports both MCP SDK types and agent-core types** — it adapts `McpServerRegistryService` to the `McpToolProvider` interface. Also owns security config and exposes the agent over HTTP (`/api/agent/chat` and the streaming `/api/agent/stream`).
- **`agent-terminal`** — the interactive terminal **front-end** (`AgentTerminalApplication`). A console-only Spring Boot app (no web server) that POSTs prompts to the server's `/api/agent/stream` SSE endpoint and renders the answer token-by-token, Claude-Code style. It is a thin HTTP client: depends on `agent-core` only to reuse the `AgentRequest` model, never on the MCP SDK or `mcp-integration`. Config under the `terminal` prefix (`server-url`, `api-key`, `model`).

This boundary is enforced by the POMs (agent-core has no MCP dependency) and exists so the agent logic stays decoupled from the MCP transport implementation. When adding agent features, keep MCP-specific types out of `agent-core`; add to the `McpToolProvider` interface and implement in `mcp-integration` instead.

### Agent loop (`AgentService.chat`)
1. Flattens all tools from all connected MCP servers into an OpenAI-format tool list, building a `toolName → serverName` reverse map.
2. Sends the prompt to the configured LLM (`OpenAiClient`) advertising those tools. The model name comes from the request, falling back to `llm.model`.
3. If the model returns `tool_calls`, routes each to the owning server via `McpToolProvider.callTool`, appends results as `tool` messages, and repeats.
4. Loop is bounded by `MAX_ITERATIONS` (10) and a wall-clock `agent.request-timeout` (default 5m).

Note: tool calls are handled whenever `tool_calls` are present **regardless of `finish_reason`** — some models set `stop` or null even with tool calls present. Preserve this behavior.

### Streaming (`AgentService.chatStream` / `/api/agent/stream`)
`chatStream` runs the exact same loop as `chat` but calls `OpenAiClient.chatStream` (`stream: true`, parses the OpenAI SSE response, reassembling content and tool-call argument fragments) and reports progress through an `AgentStreamListener` (`onContent` per text token, `onToolCall`/`onToolResult` per tool). The server's `/api/agent/stream` endpoint runs the loop on a background thread (`agentStreamExecutor`) and re-emits these as SSE events — `token` `{"text"}`, `tool` `{"name","args"}`, `tool_result` `{"name","result"}`, `done`, `error` — which `agent-terminal` consumes. Keep the streaming and non-streaming loops behaviourally identical (`runLoop` is the shared implementation).

### MCP server registry (`McpServerRegistryService`)
- Reads/writes `mcp-servers.json` in **Claude Desktop format** (`mcpServers` map; stdio = `command`/`args`/`env`, SSE = `url`/`headers`). `resolvedType()` picks STDIO vs SSE based on presence of `url`.
- Connects to all configured servers on `@PostConstruct`; closes them on `@PreDestroy`. Connection failures are recorded in `connectionErrors` (not fatal) and surfaced via `ServerInfo`.
- Spring AI's MCP auto-configuration is **deliberately disabled** (`spring.ai.mcp.client.enabled=false`) so this service owns the entire lifecycle. Don't re-enable it.
- Config file path comes from the `mcp` config prefix; supports `~/`-relative paths.

## Configuration (`application.yml`)
- `llm.provider` — active LLM provider; must match a key under `llm.providers` (`ollama`, `openrouter`, …).
- `llm.model` — default model used when a request omits one.
- `llm.providers.<name>.base-url` / `.api-key` — OpenAI-compatible API root (before `/chat/completions`) and optional key. When `api-key` is set it is sent as `Authorization: Bearer <key>` (Ollama omits it; OpenRouter requires it).
- `mcp.config-file` — path to the MCP servers JSON (default `./mcp-servers.json`).
- `agent.api-key` — if set, `/api/agent/**` requires the `X-API-Key` header; if blank, those endpoints are open (relies on network-level security). The `/api/servers/**` CRUD endpoints are currently **unauthenticated** regardless.
- `agent.request-timeout` — per-request wall-clock budget for the agent loop.

## CI
`.github/workflows/openrouter-review.yml` runs an automated OpenRouter PR review (via the `jeremyunck/openrouter-review` action) on every PR. There is no other CI build/test pipeline.
