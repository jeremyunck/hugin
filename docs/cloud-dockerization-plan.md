# Cloud Dockerization Plan

Plan for running the agent as a containerized cloud agent. The unit to ship is
the **`mcp-integration`** Spring Boot server (port 8080); `agent-terminal` stays
a thin client that connects to it from outside the container.

## Status of the original blockers

When we discussed dockerizing, three issues stood out. One is already resolved
at the config level:

| Issue | Status |
| --- | --- |
| Default LLM was Ollama on `localhost:11434` (won't exist in a container) | **Resolved** — `llm.provider` is now `openrouter` and `llm.model` is `openai/gpt-oss-120b`, a remote tool-calling model. The image only needs `OPEN_ROUTER_API_KEY`, no bundled Ollama. |
| Default MCP servers launch via `npx`/`uvx` (not in a JRE image) | Open — see §1. |
| Built-in local tools grant filesystem + shell access | Open — see §3. |

## 1. MCP server runtimes in the image

The server spawns MCP servers as **child processes** of the JVM, so whatever
runtimes those servers need must live in the same image.

- **Default web search** is `openrouter-search-mcp.py` (registered automatically
  from `search.provider: openrouter`). It needs **`python3` + the `mcp` pip
  package** and `OPEN_ROUTER_API_KEY` in the environment.
- The optional `filesystem` / `time` stdio servers in `mcp-servers.example.json`
  need **Node/npm (`npx`)** and **`uv` (`uvx`)** respectively.

Decision to make: keep the image lean (Python only, drop the npx/uvx servers
from the cloud config) or fat (bake Node + uv too). Recommendation: **lean** —
the default config only requires Python for web search; prefer SSE/remote MCP
servers over stdio ones for anything else in cloud.

Path note: `mcp.config-file` (`../mcp-servers.json`) and
`search.openrouter-script` (`../openrouter-search-mcp.py`) are relative paths.
In the image, pin them to absolute locations via Spring relaxed-binding env vars
`MCP_CONFIGFILE` and `SEARCH_OPENROUTERSCRIPT`, and copy the script to a fixed
path (e.g. `/app/openrouter-search-mcp.py`). Note `mcp-servers.json` is
`.gitignore`d and absent by default; the server falls back to the
`search.provider` auto-registration, so no file needs to be baked in.

## 2. LLM endpoint (resolved)

No work beyond the config change. The server talks to OpenRouter over HTTPS;
provide `OPEN_ROUTER_API_KEY` as a container secret. Egress to
`https://openrouter.ai` must be allowed by the cloud network policy.

## 3. Local tools = filesystem + shell access (security)

`agent.tools.enabled: true` lets the model read/write files and run shell
commands inside `workspace-root`. On a laptop that's fine; in a shared cloud
deployment it is the main attack surface. Options:

1. **Disable in cloud** — set `AGENT_TOOLS_ENABLED=false` for deployments that
   don't need code execution. Safest default.
2. **Sandbox hard** — if tools are needed: pin `agent.tools.workspace-root` to a
   dedicated absolute dir (e.g. `/workspace`), run the container as a **non-root
   user**, with a **read-only root filesystem** and the workspace as the only
   writable mount, drop Linux capabilities, and set memory/CPU limits.

Also: `agent.api-key` is blank by default, leaving `/api/agent/**` open. Set
`AGENT_API_KEY` (passed as `X-API-Key`) before exposing the server publicly.
The `/api/servers/**` CRUD endpoints are unauthenticated regardless — keep them
off the public network (gateway / network policy) until that's addressed.

## 4. Additional findings

- **Env var name inconsistency.** `OPEN_ROUTER_API_KEY` is used by the LLM
  provider, web search, the Python script, and CI; but the embedding endpoint
  (long-term memory) reads `OPEN_ROUTER_API_KEY`. Standardize on one name before
  shipping so a single secret covers everything. Low-risk follow-up, but it will
  bite anyone enabling memory.
- **No healthcheck endpoint.** There's no `spring-boot-starter-actuator`
  dependency, so there's no `/actuator/health`. Add actuator for a clean
  container `HEALTHCHECK`, or point the healthcheck at an existing endpoint.
- **Redis + embeddings are optional.** Needed only when `MEMORY_ENABLED=true`.
  Keep them out of the base image; add Redis via a compose profile when memory
  is turned on. Short-term conversation memory is in-process and needs nothing.

## 5. Proposed implementation steps

1. **Multi-stage Dockerfile** at repo root:
   - Build stage `maven:3.9-eclipse-temurin-21` → `mvn -pl mcp-integration -am
     clean package -DskipTests`, producing
     `mcp-integration/target/mcp-integration-0.0.1-SNAPSHOT.jar`.
   - Runtime stage `eclipse-temurin:21-jre`, plus `python3`/`pip` and
     `pip install mcp`. Copy the jar and `openrouter-search-mcp.py`.
   - Create a non-root user, `WORKDIR /app`, `EXPOSE 8080`,
     `ENTRYPOINT ["java","-jar","app.jar"]`.
2. **Externalize config via env**: `OPEN_ROUTER_API_KEY`, `AGENT_API_KEY`,
   `AGENT_TOOLS_ENABLED`, `AGENT_TOOLS_WORKSPACEROOT`, `MEMORY_ENABLED`,
   `MCP_CONFIGFILE`, `SEARCH_OPENROUTERSCRIPT`.
3. **`.dockerignore`** to keep `target/`, `.git/`, IDE files out of the build
   context.
4. **(Optional) `docker-compose.yml`** with the server and a `memory` profile
   that adds Redis and sets `MEMORY_ENABLED=true` + `REDIS_HOST=redis`.
5. **Add actuator** (or pick a healthcheck endpoint) and wire `HEALTHCHECK`.
6. **(Optional) CI** job to build the image on PRs.
7. **Harden** per §3 before any public exposure.

## Out of scope / decisions needed

- Lean vs. fat image (Node + uv) — §1.
- Whether cloud deployments enable local tools at all — §3.
- Standardizing the OpenRouter env var name — §4.
