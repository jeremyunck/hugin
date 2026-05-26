# Cloud Agent Deployment Plan

> **Status / sequencing (updated):** This work is now **deferred behind the
> Raspberry Pi install experience** — see
> [`raspberry-pi-install-plan.md`](./raspberry-pi-install-plan.md). The priority
> shifted to first making the agent trivially installable and runnable on a Pi
> (Ollama-style: interactive installer + a single `mcp-agent` command). That
> plan reuses the same `$AGENT_HOME` base-of-operations directory defined here,
> so it is a foundation for — not a replacement of — the cloud-agent work below,
> which remains the next phase.

Plan for turning the agent server into an installable tool that owns a **base of
operations** directory, learns the facts of the machine it runs on at init time,
and can spin up **cloud agents** — each cloning a GitHub repository into its own
subdirectory, on its own branch, to work a task.

This is complementary to [`cloud-dockerization-plan.md`](./cloud-dockerization-plan.md):
that plan containerizes the `mcp-integration` server; this one defines the
on-host home directory and the per-agent provisioning that lives inside it. They
can be combined later (the home dir becomes a mounted volume).

## Decisions taken

These were chosen up front and shape everything below:

1. **Packaging:** native install — ship the `mcp-integration` fat jar plus an
   install/init script that creates a home directory (e.g. `~/.mcp-agent`). The
   base of operations is a host directory.
2. **GitHub access:** the system `git` binary, driven with a `GITHUB_TOKEN`
   credential helper. No new Java git library; reuses the same process-exec
   approach as the existing `run_bash` tool.
3. **System facts storage:** a dedicated, **always-injected** facts record
   (reliable, not subject to similarity-recall thresholds), *and* a write to
   long-term memory when `memory.enabled=true`. This honors "save to long-term
   memory" without forcing Redis + embeddings to be configured for init to work.

## How this maps onto the current architecture

Three existing facts constrain the design:

- **`agent-core` must not depend on the MCP SDK or any transport/storage impl.**
  New cross-boundary capabilities follow the established SPI pattern
  (`McpToolProvider`, `MemoryStore`, `ConversationStore`): interface in
  `agent-core`, implementation in `mcp-integration`.
- **`Workspace` (`agent-core/.../tool/Workspace.java`) confines *all* file/shell
  access to a single immutable root,** resolved once at construction from
  `agent.tools.workspace-root`. Every local tool (`ReadFileTool`,
  `WriteFileTool`, `EditFileTool`, `ListFilesTool`, `GrepSearchTool`,
  `BashCommandTool`) is a singleton that injects this one `Workspace`. **A cloud
  agent needs its own root** (its cloned repo subdirectory), so this single-root
  assumption is the central thing this feature has to change. See §4.
- **`AgentRequest` already carries an opaque `sessionId`** used to key short-term
  conversation memory. A cloud agent's id *is* its `sessionId` — that one key
  ties together the agent's workspace, its branch, and its conversation history.

## 1. Base of operations — directory layout

A single home directory, location from `AGENT_HOME` (default `~/.mcp-agent`):

```
$AGENT_HOME/
  config/
    application.yml         # server config overrides (overrides the jar defaults)
    mcp-servers.json        # MCP server registry (mcp.config-file points here)
  agents/
    <agent-id>/             # one subdirectory per cloud agent
      <repo-name>/          # the cloned working tree; the agent's workspace root
      agent.json            # metadata: repo url, branch, status, created-at
  memory/
    system-facts.json       # deterministic system facts (see §3)
  logs/
```

- `config/` is layered on top of the packaged `application.yml` via Spring's
  `spring.config.additional-location`, so the install can be re-configured
  without rebuilding the jar.
- `agents/<agent-id>/` is the per-agent sandbox. The cloned repo inside it is the
  `Workspace` root for that agent — confinement (§4) keeps one agent out of
  another's directory and out of `config/`, `memory/`, etc.

## 2. Install & init

A small bootstrap step, runnable two ways so it works for both a fresh install
and the first server start:

- **`install.sh`** (native install): resolves `AGENT_HOME`, creates the layout,
  copies the default `application.yml`/`mcp-servers.json` into `config/`, then
  invokes the init routine. Surfaces the jar via a `mcp-agent` launcher script.
- **`McpClientApplication --init` / a Spring `ApplicationRunner`**: on startup,
  if `system-facts.json` is missing (or `--init` is passed), run environment
  introspection (§3), write the facts, and exit (for `--init`) or continue
  serving.

Init is **idempotent** — re-running refreshes the facts file and re-stores to
memory, never duplicates the directory tree.

## 3. Environment introspection + facts storage

### Probe (pure logic, `agent-core`)

New `EnvironmentProbe` producing a `SystemFacts` record. Everything is available
from the JVM with no native deps:

| Fact | Source |
| --- | --- |
| OS name / version | `System.getProperty("os.name" / "os.version")` |
| Architecture | `System.getProperty("os.arch")` |
| CPU cores | `Runtime.getRuntime().availableProcessors()` |
| Total / free RAM | `com.sun.management.OperatingSystemMXBean` |
| JVM max heap | `Runtime.getRuntime().maxMemory()` |
| Free disk in `AGENT_HOME` | `FileStore.getUsableSpace()` |
| Java version | `System.getProperty("java.version")` |
| Toolchains present | probe PATH for `git`, `node`/`npx`, `python3`, `uvx`, `docker` |
| Network egress hint | optional reachability check to the configured LLM base-url |

The "facts that affect capabilities" are the actionable ones: **git present?**
(cloud agents are impossible without it), **RAM/cores** (how many concurrent
agents are safe), **toolchains** (which MCP stdio servers can run), **disk**
(how many repos can be cloned).

### Storage (per the decision in §Decisions)

- **Deterministic facts store** — write `SystemFacts` as JSON to
  `$AGENT_HOME/memory/system-facts.json`. On every agent run, inject a compact
  summary as a system message (alongside the existing `TOOL_SYSTEM_PROMPT`), so
  the model *always* knows the machine's capabilities — no fuzzy recall, no
  Redis required. This is a new always-on `SystemFactsService` in `agent-core`,
  read by `AgentService.runLoop` the same way `memoryService`/`conversationMemory`
  are consulted today.
- **Long-term memory (optional)** — when `memory.enabled=true`, also call
  `MemoryService.remember(...)`-style storage so the facts become a recallable
  long-term memory, satisfying the literal "save to long-term memory" goal. Best-
  effort and skipped silently when memory is off.

## 4. Per-agent workspace (the central refactor)

Today `Workspace` is a singleton root. To give each cloud agent its own confined
directory, the workspace must be resolved **per agent at tool-execution time**.

- Refactor `Workspace` so its root is a constructor argument (a `Path`), and add
  a `WorkspaceFactory` that mints a `Workspace` rooted at any directory while
  keeping the same symlink/`..` confinement logic. The existing single-workspace
  behavior becomes the default (root = `agent.tools.workspace-root`).
- Make local tools resolve the active workspace at call time instead of holding
  a singleton. **Recommended:** widen the `LocalTool` SPI to
  `execute(Map<String,Object> args, ToolContext ctx)` where `ToolContext`
  carries the resolved `Workspace`; `AgentService.executeToolCall` looks up the
  agent's workspace (by `sessionId`) and passes it. This is explicit and keeps
  tools stateless — at the cost of touching all six tools and their tests.
  - *Lower-churn fallback:* a thread-bound `WorkspaceContext` set at the top of
    `runLoop` (the loop runs on one `agentStreamExecutor` thread per request),
    with tools reading `WorkspaceContext.current()`. Less code churn but
    introduces hidden thread-local state — note it as the trade-off.
- A `WorkspaceRegistry` maps `agent-id → Workspace` (rooted at
  `agents/<agent-id>/<repo>`). Requests with no cloud-agent context fall back to
  the default workspace, so existing `/api/agent/**` behavior is unchanged.

This refactor is the riskiest part and is sequenced first among the cloud-agent
work (Phase 2) precisely so the rest builds on a clean per-agent root.

## 5. GitHub repository provisioning (git CLI + token)

New SPI `RepositoryProvisioner` in `agent-core`, git-CLI implementation in
`mcp-integration` (mirrors the `MemoryStore`/`McpToolProvider` split):

```
interface RepositoryProvisioner {
    ProvisionedRepo clone(String repoUrl, Path targetDir, String branch);
}
```

The impl shells out to `git` (same `ProcessBuilder` approach as `BashCommandTool`):

1. `git clone <repoUrl> <agents/<agent-id>/<repo-name>>`
2. `git checkout -b <unique-branch>` — branch name e.g.
   `agent/<task-slug>-<short-agent-id>` to guarantee uniqueness.
3. Record the resolved path + branch in `agent.json`.

**Auth:** `GITHUB_TOKEN` from the environment, supplied to git via a credential
helper / `GIT_ASKPASS` (preferred over embedding the token in the remote URL so
it doesn't land in `.git/config`). Clone/push fail clearly when the token is
missing or lacks scope.

**Finish (optional, later phase):** commit the agent's changes, `git push -u
origin <branch>`, and — only if explicitly requested — open a PR via the same
token. Push is a shared-state action and stays opt-in.

## 6. Cloud agent lifecycle + API

Orchestration in a `CloudAgentService` (in `agent-core`, depending only on the
`RepositoryProvisioner` + `WorkspaceFactory` SPIs, so the boundary holds):

1. Allocate `agent-id` (UUID) → this is the `sessionId`.
2. `RepositoryProvisioner.clone(repoUrl, agents/<id>/, branch)`.
3. Register the agent's `Workspace` in the `WorkspaceRegistry`.
4. Run the existing agent loop (`AgentService.chatStream`) with the task prompt
   and `sessionId = agent-id`; tools are now confined to the cloned repo.
5. On completion, persist status to `agent.json`; optionally commit/push (§5).

New REST surface in `mcp-integration` (reusing the SSE machinery in
`AgentController`):

| Endpoint | Purpose |
| --- | --- |
| `POST /api/agents` | Create: `{repoUrl, task, branch?, model?}` → clone, branch, run; streams progress like `/api/agent/stream`. |
| `GET /api/agents` | List agents + status (reads `agents/*/agent.json`). |
| `GET /api/agents/{id}` | One agent's metadata/status. |
| `DELETE /api/agents/{id}` | Stop + delete its subdirectory (disk reclaim). |

These live under the same `agent.api-key` protection as `/api/agent/**`.

## 7. Configuration additions

```yaml
agent:
  home: ${AGENT_HOME:~/.mcp-agent}     # base of operations
  cloud:
    enabled: ${CLOUD_AGENTS_ENABLED:false}   # gate the /api/agents surface
    max-concurrent: 3                        # derived/capped from probed RAM+cores
    github-token: ${GITHUB_TOKEN:}           # clone/push credential
    branch-prefix: agent                     # <prefix>/<slug>-<id>
    cleanup-on-complete: false               # keep clones for inspection by default
```

`mcp.config-file` and `agent.tools.workspace-root` default into `$AGENT_HOME`
(`config/mcp-servers.json` and `agents/`).

## 8. Security & operational considerations

- **Cloning arbitrary repos amplifies the `run_bash` risk.** The per-agent
  workspace confinement (§4) is what keeps a task in repo A from touching repo B,
  `config/`, or `memory/`. Verify the confinement holds for the new roots before
  enabling cloud agents in any shared environment.
- **`GITHUB_TOKEN` is a secret** — scope it to the repos in play; never write it
  into `.git/config` (use the credential helper); keep it out of logs and
  `agent.json`.
- **Disk growth** — each agent is a full clone. `DELETE /api/agents/{id}` and the
  `cleanup-on-complete` flag bound it; surface free-disk from the probe.
- **Concurrency** — `Workspace` was a singleton; after §4 multiple agents run in
  parallel on `agentStreamExecutor`. Cap concurrency from the probed cores/RAM
  (`agent.cloud.max-concurrent`).
- **Push/PR are shared-state actions** — keep them opt-in and never on by
  default, consistent with the repo's "confirm before risky/visible actions"
  posture.
- `agent.api-key` should be set before exposing `/api/agents`; the existing
  `/api/servers/**` endpoints remain unauthenticated (pre-existing gap noted in
  the dockerization plan).

## 9. Phased implementation

1. **Home dir + init + facts (low risk).** `AGENT_HOME` resolution, directory
   bootstrap, `install.sh`, `EnvironmentProbe` + `SystemFacts`,
   `SystemFactsService` always-injected summary, optional long-term-memory write.
   No change to the agent loop's tool execution.
2. **Per-agent workspace refactor.** `Workspace` root as a constructor arg,
   `WorkspaceFactory`, `WorkspaceRegistry`, `ToolContext`-based `LocalTool` SPI
   (or the thread-bound fallback). Existing `/api/agent/**` behavior must stay
   identical (default workspace). Heaviest-churn step; do it before the lifecycle.
3. **Repo provisioning + cloud-agent lifecycle.** `RepositoryProvisioner` SPI +
   git-CLI impl, `CloudAgentService`, `POST/GET/DELETE /api/agents`.
4. **Finish + hardening.** Commit/push/PR (opt-in), cleanup, concurrency caps,
   disk reporting, docs.

## 10. Open decisions / out of scope

- **Auto-PR on completion** — left opt-in; whether to default it on is a product
  call.
- **Multi-tenant auth** — `/api/agents` uses the single `agent.api-key`; per-user
  isolation/quotas are out of scope here.
- **Container variant** — folding `$AGENT_HOME` into the Docker image as a mounted
  volume is deferred to a merge with the dockerization plan.
- **Resuming/persisting a running agent across restarts** — `agent.json` records
  status, but live in-flight resumption is not covered.
