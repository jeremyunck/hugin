# mcp-client

A Spring Boot AI agent that connects to [Model Context Protocol](https://modelcontextprotocol.io) (MCP) servers and lets a local Ollama model use their tools to answer prompts.

## Prerequisites

- **Java 21** and **Maven**
- **[Ollama](https://ollama.com)** running locally with a tool-calling model pulled:
  ```bash
  ollama pull llama3.2
  ollama serve            # exposes http://localhost:11434
  ```
- Runtimes for the MCP servers you configure. The default `mcp-servers.json` uses:
  - `npx` (Node.js) for the filesystem server
  - `uvx` ([uv](https://docs.astral.sh/uv/)) for the time server

## Configure MCP servers

Servers are declared in `mcp-servers.json` (repo root) using the standard Claude Desktop format:

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

## Build

```bash
mvn clean install
```

## Run

```bash
mvn -pl mcp-integration spring-boot:run
```

The app starts on **port 8080**. `mcp-integration` is the only runnable module; `agent-core` and `mcp-client` are libraries.

## Usage

Send a prompt to the agent:

```bash
curl -X POST http://localhost:8080/api/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"prompt": "What time is it in Tokyo?", "model": "llama3.2"}'
```

`model` is optional and falls back to `ollama.model` from configuration.

Manage MCP servers at runtime:

```bash
curl http://localhost:8080/api/servers                 # list servers + connection status
curl http://localhost:8080/api/servers/time/tools      # list a server's tools
curl -X POST http://localhost:8080/api/servers/time/reconnect
```

## Configuration

Settings live in `mcp-integration/src/main/resources/application.yml`:

| Key | Description | Default |
| --- | --- | --- |
| `ollama.base-url` | Ollama OpenAI-compatible endpoint | `http://localhost:11434` |
| `ollama.model` | Default model (must support tool calling) | `llama3.2` |
| `mcp.config-file` | Path to the MCP servers JSON (supports `~/`) | `./mcp-servers.json` |
| `agent.api-key` | If set, `/api/agent/**` requires the `X-API-Key` header; if blank, those endpoints are open | _(blank)_ |
| `agent.request-timeout` | Per-request wall-clock budget for the agent loop | `5m` |
