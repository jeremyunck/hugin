# Cloud Agent Platform — Audit & Build Plan

**Goal:** turn this repo into the backend for a **$5/month bring-your-own-OpenRouter-key cloud
agent**. A user connects a GitHub repo, gives an instruction, and the platform clones the repo into
an isolated sandbox, runs the agent, and opens a pull request — Claude Code / Cursor style. A
separate front-end app (out of scope for this repo) talks to this backend over HTTP. The old
terminal UI has been removed from this repo.

This document audits what exists today, lists what must be **added** and **fixed**, compares the
product to competitors, and recommends a hosting approach. It is a plan only — no code changes are
included.

---

## 1. Executive summary

The repo is a solid **single-tenant agent engine**: a clean agent loop (`AgentService`), pluggable
OpenAI-schema LLM client, MCP tool integration, built-in file/shell tools sandboxed to a workspace,
and the early bones of a cloud-agent feature (`CloudAgentService`, `CloudAgentController`,
`GitRepositoryProvisioner`). It is **not yet a multi-tenant SaaS**, and the headline feature — "open
a PR" — is **not implemented**.

The three things that block the product entirely:

1. **No commit / push / PR step.** `CloudAgentService.run()` clones a repo, runs the agent loop, and
   marks the agent `DONE`. Nothing commits the agent's edits, pushes the branch, or opens a PR.
   `grep` for `push`/`pull request`/`commit` in `src/main` returns nothing.
2. **No multi-tenancy and no real BYOK.** There are no user accounts, and the OpenRouter key is a
   single server-wide value baked into the `OpenAiClient` bean at startup
   (`OpenAiClient.java:73,87`). "Bring your own key" is impossible without per-request key plumbing.
3. **No tenant isolation.** The agent loop — including the `run_bash` arbitrary-shell tool
   (`BashCommandTool`) — runs **inside the backend JVM process**. In a multi-tenant deployment one
   user's agent could read another tenant's cloned repo, env vars, and secrets. This is the single
   biggest architectural change required.

Everything else (billing, persistence, quotas, auth, observability) is standard SaaS plumbing that
sits on top of those three.

**Recommended shape:** split into a **control plane** (this Spring Boot app: accounts, billing, API,
job orchestration, GitHub/PR integration) and **ephemeral per-task sandboxes** (one isolated
microVM/container per agent run, scaled to zero between tasks). The agent loop moves out of the
control-plane process and into the sandbox.

---

## 2. Current state

### What works and is reusable as-is
- **Agent loop** (`AgentService.runLoop`): tool flattening, MCP + local tool routing, bounded
  iterations, wall-clock timeout, streaming + non-streaming parity. Solid core.
- **LLM client** (`OpenAiClient`): generic OpenAI-schema chat-completions, streaming SSE parsing.
- **Built-in tools** (`tool/`): `read_file`, `write_file`, `edit_file`, `list_files`, `grep_search`,
  `run_bash`, all confined to a `Workspace` root with symlink-escape protection (`Workspace.resolve`).
- **Per-session workspace routing** (`WorkspaceRegistry` + `WorkspaceFactory`): already maps a
  session/agent id → its own cloned working tree. Good foundation for per-task isolation.
- **MCP server registry**: full connect/disconnect/reconnect lifecycle + CRUD, now hosted inside `mcp-integration`.
- **Memory layers**: in-process short-term conversation memory; optional Redis-backed long-term
  semantic memory. Both best-effort.
- **Module boundary discipline**: `agent-core` has no MCP/transport/storage dependency; SPIs
  (`McpToolProvider`, `MemoryStore`, `ConversationStore`, `RepositoryProvisioner`) keep it decoupled.

### Cloud-agent scaffolding already present (incomplete)
- `CloudAgentController` — `POST/GET/DELETE /api/agents`, streams SSE like `/api/agent/stream`.
- `CloudAgentService` — allocates an id, clones via `RepositoryProvisioner`, registers the working
  tree as the session workspace, runs the loop, persists `agent.json`.
- `GitRepositoryProvisioner` — shells out to `git clone` + `git checkout -b`.
- `CloudAgentProperties` (`agent.cloud.*`) — `enabled`, `maxConcurrent`, `githubToken`,
  `branchPrefix`, `cleanupOnComplete`. Gated off by default.

### Confirmed gaps in the existing cloud code
- **No commit/push/PR** (see §1).
- **Agents are not durable.** `CloudAgentService.agents` is an in-memory `ConcurrentHashMap`;
  `agent.json` is written per agent dir but **never reloaded on startup**. A restart loses all agent
  state and the `GET /api/agents` list.
- **`maxConcurrent` is declared but not enforced** — no semaphore/queue gates concurrent runs.
- **Synchronous, connection-bound execution.** A run lives for the duration of one SSE connection on
  a thread-pool thread. A dropped connection or a long task (minutes) has no durable job behind it.
- **Single global GitHub token** (`agent.cloud.github-token`) — not per-user.
- **`GitRepositoryProvisioner.configureAuth` is fragile/insecure.** It sets `GIT_ASKPASS=sh` plus a
  `credential.helper` that does `!echo password=<token>` (no username, mixed mechanisms, token on a
  command line). Should be replaced with a proper per-user credential mechanism (see §4.3).

---

## 3. Target product definition

| Aspect | Target |
|---|---|
| Pricing | $5/month flat, BYO OpenRouter key (user pays inference on their own key) |
| Trigger | User submits `{repoUrl, task, baseBranch, model}` from the front-end |
| Execution | Clone → isolated sandbox → agent loop edits code → commit → push → **open PR** |
| LLM | Any OpenRouter model, selected per request, billed to the **user's** key |
| Auth | User accounts; GitHub connected via OAuth/GitHub App (not a shared PAT) |
| Output | A PR URL + a streamed/recorded transcript of what the agent did |
| Front-end | Separate app (not in this repo); this repo is the API backend |

The economic thesis: because inference is BYOK, the platform's marginal cost per task is **sandbox
compute + git/PR bandwidth**, not tokens. The $5 covers control-plane + bounded sandbox minutes.
This only works if sandboxes are ephemeral and scale to zero (see §7).

---

## 4. What needs to be ADDED

Ordered roughly by priority. Items marked **[MVP]** are required for a first paid release.

### 4.1 PR workflow — the missing core **[MVP]**
The agent currently edits files but nothing leaves the sandbox. Add a post-run finalization step:

1. Detect changes (`git status --porcelain`); if none, mark the agent `DONE` with "no changes".
2. `git add -A` and commit with a generated message (task summary + co-author trailer).
3. Push the working branch to `origin` using the **user's** GitHub credential.
4. Open a PR via the GitHub REST API (`POST /repos/{owner}/{repo}/pulls`) targeting the base branch;
   return the PR URL.
5. Surface the PR URL in `AgentInfo`/SSE (`pr_opened` event) and persist it.

Design notes:
- Add a `PullRequestPublisher` SPI in `agent-core` (keep `agent-core` free of any GitHub SDK), with
  a `mcp-integration` implementation (git CLI for push + a thin GitHub REST call, or an existing Java
  GitHub client).
- Decide commit/PR identity policy (committer email, whether to sign).
- Handle the agent producing a partial/failed change set gracefully (draft PR vs. no PR).
- Consider letting the agent iterate against CI feedback later (post-MVP).

### 4.2 Per-user BYOK key plumbing **[MVP]**
Today `OpenAiClient` is a singleton with the key baked into a `BearerAuthInterceptor`
(`OpenAiClient.java:87-88`). For BYOK, the **caller's** OpenRouter key must reach the LLM call:

- Add the user's key to the per-task execution context (never in the request body from the browser —
  store it server-side encrypted, inject into the sandbox/loop).
- Refactor `OpenAiClient` so the API key is a **per-call parameter** (or build a short-lived client
  per task) rather than constructor-injected global state. The embedding client
  (`EmbeddingClient`) has the same issue if long-term memory is offered per-user.
- Validate the key (cheap `GET /key` or models call) before starting a billable run; surface bad-key
  errors clearly.
- Encrypt keys at rest (envelope encryption / KMS); never log them.

### 4.3 GitHub identity per user **[MVP]**
Replace the single `agent.cloud.github-token` with per-user GitHub auth:

- **Preferred: a GitHub App** — users install it on the repos they want the agent to touch; the
  backend mints short-lived installation tokens per task. Cleaner permissions, easy revocation, and
  the PR is attributed to the app.
- Alternative: GitHub OAuth (user token). Simpler but broader scope and token custody burden.
- Replace `GitRepositoryProvisioner.configureAuth` with injection of a short-lived token per clone
  (e.g. `https://x-access-token:<token>@github.com/...` remote, or a per-process credential helper),
  scoped to one task and discarded after.

### 4.4 Accounts, authn/authz **[MVP]**
- User model + signup/login (or delegate identity to GitHub OAuth / an IdP like Clerk/Auth0/Supabase
  to avoid building password infra).
- Per-user API tokens for the front-end to call this backend; replace the single shared
  `agent.api-key` (`SecurityConfig`) with per-user token validation and tenant scoping.
- Authorize every `/api/agents/{id}` action against the owning user (currently any caller can
  GET/DELETE any agent).

### 4.5 Durable persistence & job model **[MVP]**
- Introduce a database (Postgres) for users, subscriptions, agent runs, PR results, usage. Replace
  the in-memory `agents` map and the `agent.json` files.
- Make a run a **durable async job**: enqueue on submit, return an id immediately, execute on a
  worker, stream progress over SSE/WebSocket but reconnectable (replay last events). A dropped
  browser connection must not kill or orphan a run.
- A queue (Postgres-backed, Redis, or SQS) decouples submission from execution and enforces
  concurrency limits (the unenforced `maxConcurrent`).

### 4.6 Tenant isolation / real sandbox **[MVP — security-critical]**
The agent loop + `run_bash` must **not** run in the shared backend process. Each run needs its own
isolated environment with no access to other tenants' data or the platform's secrets. See §7 for
hosting; architecturally:

- Control plane spawns one **ephemeral sandbox per task** (microVM/container), injects the user's
  OpenRouter key + a short-lived GitHub token + the task, runs the loop there, captures the
  transcript and PR result, then destroys the sandbox.
- The sandbox image bundles `git` and the agent runtime; egress is locked down (allow OpenRouter +
  GitHub only) to limit exfiltration and abuse.
- Per-task resource caps (CPU/mem/disk/time) and forced teardown.

### 4.7 Billing & subscription **[MVP]**
- Stripe (or similar): $5/mo subscription, webhook-driven entitlement, gate run submission on an
  active subscription.
- Customer portal for cancel/update; handle dunning/past-due → disable runs.

### 4.8 Quotas, rate limits, cost controls **[MVP]**
- Per-user concurrent-run cap and monthly run/minute quota (protects platform compute under a flat
  $5 price).
- Hard per-run ceilings: wall-clock (exists: `agent.request-timeout`), max iterations (exists:
  `MAX_ITERATIONS=10`), max sandbox minutes, max repo size / clone depth (use shallow clones).
- Abuse prevention: validate/allowlist repo hosts, block runs against arbitrary URLs, scan task
  inputs for prompt-injection-driven abuse, audit log.

### 4.9 Observability & ops
- Metrics (Micrometer/Prometheus), structured logs with tenant/run ids, traces.
- Per-run audit record (who, what repo, what model, outcome, PR).
- Alerting on failure rate, sandbox exhaustion, queue depth.
- Expand actuator exposure beyond `health` for internal monitoring (kept off the public network).

### 4.10 API surface for the front-end
- Stabilize/version the agent API (`/api/v1/...`), document with the existing springdoc/OpenAPI.
- Endpoints: submit run, list/get runs (with PR + status), stream/replay events, cancel run,
  manage GitHub connection, manage OpenRouter key, account/billing status.
- Tighten CORS to the front-end origin(s) — currently `allowedOriginPatterns("*")` **with**
  `allowCredentials(true)` (`SecurityConfig.corsConfigurationSource`), which is permissive and
  invalid for credentialed cross-origin in many browsers.

---

## 5. What needs to be FIXED (existing code)

| # | Issue | Location | Fix |
|---|---|---|---|
| 1 | PR workflow absent | `CloudAgentService.run` | Add commit/push/PR finalization (§4.1) |
| 2 | OpenRouter key is global singleton | `OpenAiClient.java:73,87-88` | Make key per-call for BYOK (§4.2) |
| 3 | Fragile/insecure git auth | `GitRepositoryProvisioner.configureAuth` | Per-user short-lived token (§4.3) |
| 4 | Agents not durable; lost on restart | `CloudAgentService.agents`, `agent.json` | DB-backed runs (§4.5) |
| 5 | `maxConcurrent` not enforced | `CloudAgentProperties` / `CloudAgentService` | Queue + semaphore (§4.5/4.8) |
| 6 | `/api/servers/**` unauthenticated | `SecurityConfig` (only `/api/agent/**` secured) | Authenticate or remove from public surface |
| 7 | Permissive CORS with credentials | `SecurityConfig.corsConfigurationSource` | Pin to front-end origins (§4.10) |
| 8 | Any caller can GET/DELETE any agent | `CloudAgentController` | Authorize by owner (§4.4) |
| 9 | `run_bash` arbitrary shell in shared process | `BashCommandTool` | Run inside per-task sandbox only (§4.6) |
| 10 | Single shared `agent.api-key` | `SecurityConfig` | Per-user tokens (§4.4) |
| 11 | MCP stdio servers spawn host subprocesses | MCP registry | For cloud, restrict to remote/SSE MCP or run inside sandbox |
| 12 | Secrets via plain env (`OPEN_ROUTER_API_KEY`, `GITHUB_TOKEN`) | `application.yml`, Docker | Secret manager + encryption at rest |

### Cleanup / removal
- **Keep the terminal UI removed.** The repo should stay server-focused; avoid reintroducing a
  built-in terminal UI unless it is a separate project.
- **Re-scope the Raspberry-Pi appliance story.** `docs/raspberry-pi-install-plan.md`, `install.sh`,
  and the systemd unit target a single-user self-hosted appliance — a different product from a
  multi-tenant SaaS. Keep them only if a self-host tier is intended; otherwise retire to avoid
  confusion.
- **Disable/guard memory layers per-tenant.** Long-term Redis memory is global; if offered, it must
  be namespaced per user, or left off for the cloud product.

---

## 6. Competitor comparison

The space splits into **IDE/CLI assistants** (you drive, local), **cloud async agents** (you assign,
they run remotely and open a PR), and **open-source self-hostable agents**. This product is a *cloud
async agent* with a *BYOK, flat-price* twist.

| Product | Category | Hosting | LLM / cost model | Opens PRs | Price | Notable |
|---|---|---|---|---|---|---|
| **This project** | Cloud async agent | Cloud sandbox (proposed) | **BYO OpenRouter key** (any model) | Goal | **$5/mo flat** | Model-agnostic, cheapest flat tier |
| **Cursor** (background agents) | IDE + cloud agents | Cloud sandbox | Bundled; usage-based on top of sub | Yes | ~$20/mo Pro + usage | Best-in-class IDE UX; agents are an add-on |
| **Claude Code** (+ web) | CLI + cloud sessions | Local or cloud sandbox | Anthropic models; sub or API spend | Yes | Claude sub / API metered | Strong agentic coding; Anthropic-only models |
| **OpenHands** (ex-OpenDevin) | Open-source agent (+ Cloud) | Self-host Docker runtime, or OpenHands Cloud credits | **BYO key when self-hosted**; credits on cloud | Yes (resolves issues → PR) | OSS free; cloud = credits | Closest open analog; self-host = $0 + your infra |
| **Devin** (Cognition) | Cloud autonomous SWE | Cloud sandbox | Bundled (ACU usage) | Yes | ~$20 entry, usage-based | Most autonomous; can get expensive |
| **GitHub Copilot coding agent** | Cloud async agent | GitHub Actions ephemeral env | Bundled | Yes (assign issue → PR) | Part of Copilot sub | Deep GitHub-native integration |
| **OpenAI Codex** (cloud) | Cloud async agent | Cloud sandbox | Bundled (OpenAI models) | Yes | Part of ChatGPT plans | Parallel tasks; OpenAI-only |
| **Google Jules** | Cloud async agent | Cloud VM | Bundled (Gemini) | Yes | Free beta → tiered | Repo clone → VM → PR |

**Where this product can win**
- **Price + BYOK**: a flat $5/mo with no inference markup undercuts every bundled competitor for
  users who already hold OpenRouter credit. Most rivals monetize inference; this one monetizes only
  the orchestration.
- **Model freedom**: any OpenRouter model (open-weight or frontier) vs. single-vendor lock-in
  (Claude/OpenAI/Gemini).

**Where it's behind (and what closes the gap)**
- No IDE/editor experience — purely "assign a task, get a PR" (front-end is separate). Fine if the
  positioning is async PR automation, not pair programming.
- Maturity: competitors have robust sandboxes, CI-feedback loops, repo-wide context/indexing, and PR
  review iteration. MVP should at least: open a PR, stream the transcript, and respect repo
  structure. CI-feedback iteration and repo indexing are strong fast-follows.
- **OpenHands is the key benchmark.** Since it's open-source and self-hostable with BYO key for
  free, the paid pitch must be "we run, isolate, and bill the sandbox for you for $5 so you don't
  operate Docker runtimes or babysit infra." The value is **managed convenience + isolation**, not
  the agent loop itself.

---

## 7. Hosting recommendation

### Architecture: control plane + ephemeral sandboxes
Split responsibilities so the multi-tenant security boundary is a real VM/container, not a Java path
check:

- **Control plane** (this Spring Boot app): API, auth, billing, Postgres, job queue, GitHub App
  token minting, PR orchestration, SSE/WebSocket streaming. Always-on, small, horizontally scalable,
  holds **no tenant code execution**.
- **Sandbox runner** (per task): an ephemeral, isolated environment that clones the repo, runs the
  agent loop + `run_bash`, and reports transcript + diff/PR back. Created on submit, destroyed on
  completion, **scaled to zero** when idle. Locked-down egress (OpenRouter + GitHub only).

This is the change that makes `run_bash` and BYOK safe in multi-tenant.

### Recommended primary: Fly.io Machines
- Per-second-billed, fast-booting **microVMs** (Firecracker) created/destroyed via API — a natural
  fit for "one sandbox per task, scale to zero."
- Hardware-level isolation between tenants without operating your own hypervisor.
- Run the control plane as a normal Fly app; spawn a Machine per agent run.
- Cost aligns with the BYOK thesis: you pay only for the minutes a task actually runs.

### Alternatives (by trade-off)
| Option | Isolation | Cost at scale | Ops burden | When to pick |
|---|---|---|---|---|
| **Fly Machines** (recommended) | microVM (Firecracker) | Low (per-second, scale-to-zero) | Low | Default; best fit for ephemeral sandboxes |
| **Hetzner/bare VPS + Firecracker or gVisor** | microVM / gVisor | **Lowest** | High (you run the hypervisor + pool mgmt) | Cost-obsessed at volume, have ops capacity |
| **Kubernetes + gVisor (GKE Sandbox) or Kata** | gVisor / Kata | Medium | Medium-High | Already on K8s; want pod-per-task |
| **AWS Fargate / ECS task per run** | container (shared kernel) | Higher; slower cold start | Low-Medium | Deep AWS shop; accept weaker isolation + cost |
| **Single big VM, all tenants in one process** (today) | **None (path check only)** | — | — | **Not acceptable for multi-tenant** |

Avoid plain shared-kernel containers as the *only* boundary for untrusted `run_bash`; prefer microVM
(Firecracker) or gVisor/Kata.

### Unit economics sanity check (flat $5/mo, BYOK)
- Revenue ≈ $5/user/mo; inference cost = $0 to platform (user's OpenRouter key).
- Platform cost ≈ control-plane (fixed, amortized) + **sandbox minutes** + Postgres + egress.
- Viability hinges on bounding sandbox minutes/user/month (quotas in §4.8) and scaling sandboxes to
  zero. A heavy user running many long tasks can erase the margin — hence per-user run/minute caps
  and a fair-use policy are **product requirements, not nice-to-haves**.
- Keep clones shallow, cap repo size, and reap sandboxes aggressively.

### Data services
- **Postgres** (managed: Fly Postgres / Neon / RDS) — users, subscriptions, runs, results.
- **Redis** — queue/concurrency + optional long-term memory (already supported).
- **Secret storage** — KMS/secret manager for encrypted OpenRouter keys + GitHub App private key.
- **Object storage** — transcripts/logs/artifacts if retained.

---

## 8. Phased roadmap

**Phase 0 — Repo cleanup (low risk)**
- Remove the terminal UI; re-scope/retire the Pi appliance assets; tighten CORS and `/api/servers`
  auth; document the target API.

**Phase 1 — MVP (single isolated runner, real PRs)**
- PR workflow (§4.1); per-user BYOK key plumbing (§4.2); GitHub App identity (§4.3);
  accounts + per-user tokens (§4.4); Postgres-backed durable runs + queue (§4.5);
  one sandbox per task on Fly Machines (§4.6); Stripe $5/mo (§4.7); basic quotas (§4.8).

**Phase 2 — Harden & scale**
- Concurrency limits enforced, abuse prevention, observability/alerting, reconnectable streaming,
  egress lockdown, secret manager, audit logging.

**Phase 3 — Differentiate**
- CI-feedback iteration (agent reads failing checks and pushes fixes), repo indexing/context,
  multiple PR revisions per task, scheduled/triggered runs, model recommendations.

---

## 9. Open questions / decisions

1. **Identity provider**: build auth, or delegate to GitHub OAuth / Clerk / Auth0 / Supabase?
2. **GitHub integration**: GitHub App (recommended) vs. user OAuth tokens — affects permissions and
   PR attribution.
3. **Self-host tier?** Keep the Pi/appliance path as a free self-host option (vs. OpenHands), or drop
   it to focus the SaaS?
4. **Where does the agent loop run** — entirely in the sandbox (recommended), or loop in control
   plane with only tool calls remoted? Former is cleaner for isolation.
5. **OpenRouter key custody**: store encrypted server-side (smoother UX) vs. inject per-run from the
   front-end (less custody risk). BYOK billing/validation UX depends on this.
6. **Free trial / abuse**: how to prevent throwaway accounts from burning sandbox minutes before the
   $5 ever lands.
7. **Repo scope**: GitHub-only at launch, or GitLab/Bitbucket later? Public + private?
