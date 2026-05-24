# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

Maven multi-module project (Java 21, Spring Boot 3.5.x). Run from the repo root.

```bash
mvn clean install            # build all modules + run tests
mvn -pl agent-core test      # test a single module
mvn -pl mcp-integration spring-boot:run   # run the app (only mcp-integration is runnable)

# run a single test class / method
mvn -pl agent-core test -Dtest=AgentServiceTest
mvn -pl agent-core test -Dtest=AgentServiceTest#methodName
```

`mcp-integration` is the only module with a main class (`McpClientApplication`); `agent-core` and `mcp-client` are libraries. The app serves on port 8080.

### Runtime prerequisites
- **Ollama** running at `http://localhost:11434` exposing the OpenAI-compatible `/v1` API, with a tool-calling model pulled (e.g. `llama3.2`, `mistral-nemo`). Configured in `mcp-integration/src/main/resources/application.yml`.
- **MCP server runtimes**: the default `mcp-servers.json` launches stdio servers via `npx` (filesystem) and `uvx` (time), so Node/npm and uv must be on PATH for those to connect.

## Architecture

Three modules with a deliberate dependency rule: **`agent-core` must never depend on the MCP SDK.**

- **`agent-core`** — the LLM agent loop and Ollama HTTP client. Pure logic; depends only on Spring base + Jackson. It talks to MCP only through the `McpToolProvider` interface (`agent-core/.../McpToolProvider.java`), which it does *not* implement.
- **`mcp-client`** — the MCP server registry. Owns the full lifecycle (connect/disconnect/reconnect) of MCP servers via the MCP Java SDK, plus a `/api/servers` CRUD REST API. No main class.
- **`mcp-integration`** — the runnable Spring Boot app. Wires the other two together. `McpToolProviderImpl` is **the only class that imports both MCP SDK types and agent-core types** — it adapts `McpServerRegistryService` to the `McpToolProvider` interface. Also owns security config.

This boundary is enforced by the POMs (agent-core has no MCP dependency) and exists so the agent logic stays decoupled from the MCP transport implementation. When adding agent features, keep MCP-specific types out of `agent-core`; add to the `McpToolProvider` interface and implement in `mcp-integration` instead.

### Agent loop (`AgentService.chat`)
1. Flattens all tools from all connected MCP servers into an OpenAI-format tool list, building a `toolName → serverName` reverse map.
2. Sends the prompt to Ollama advertising those tools.
3. If the model returns `tool_calls`, routes each to the owning server via `McpToolProvider.callTool`, appends results as `tool` messages, and repeats.
4. Loop is bounded by `MAX_ITERATIONS` (10) and a wall-clock `agent.request-timeout` (default 5m).

Note: tool calls are handled whenever `tool_calls` are present **regardless of `finish_reason`** — some models set `stop` or null even with tool calls present. Preserve this behavior.

### MCP server registry (`McpServerRegistryService`)
- Reads/writes `mcp-servers.json` in **Claude Desktop format** (`mcpServers` map; stdio = `command`/`args`/`env`, SSE = `url`/`headers`). `resolvedType()` picks STDIO vs SSE based on presence of `url`.
- Connects to all configured servers on `@PostConstruct`; closes them on `@PreDestroy`. Connection failures are recorded in `connectionErrors` (not fatal) and surfaced via `ServerInfo`.
- Spring AI's MCP auto-configuration is **deliberately disabled** (`spring.ai.mcp.client.enabled=false`) so this service owns the entire lifecycle. Don't re-enable it.
- Config file path comes from the `mcp` config prefix; supports `~/`-relative paths.

## Configuration (`application.yml`)
- `ollama.base-url` / `ollama.model` — Ollama endpoint and default model.
- `mcp.config-file` — path to the MCP servers JSON (default `./mcp-servers.json`).
- `agent.api-key` — if set, `/api/agent/**` requires the `X-API-Key` header; if blank, those endpoints are open (relies on network-level security). The `/api/servers/**` CRUD endpoints are currently **unauthenticated** regardless.
- `agent.request-timeout` — per-request wall-clock budget for the agent loop.

## CI
`.github/workflows/openrouter-review.yml` runs an automated OpenRouter PR review (via the `jeremyunck/openrouter-review` action) on every PR. There is no other CI build/test pipeline.
