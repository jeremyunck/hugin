# Cloud Agent Platform — Prioritized Build Checklist

Derived from `cloud-platform-audit.md`. Items are ordered by priority: Phase 0 (prep) → Phase 1 (MVP blockers, ordered by criticality) → Phase 2 (hardening) → Phase 3 (differentiation).

---

## Phase 0 — Repo Cleanup *(low risk, do first)*

- [x] Remove `agent-terminal` module — delete module dir, remove from parent `pom.xml`, prune `README`/`CLAUDE.md`/`install.sh`/`scripts/` references
- [x] Tighten CORS — replace `allowedOriginPatterns("*")` + `allowCredentials(true)` in `SecurityConfig.corsConfigurationSource` with explicit front-end origins
- [x] Authenticate `/api/v1/servers/**` — all `/api/v1/**` endpoints now secured via the same API key filter; swagger/actuator open
- [x] Re-scope or retire the Raspberry Pi appliance assets (`install.sh`, `scripts/mcp-agent*`, systemd unit) — deleted
- [x] Version the existing endpoints under `/api/v1/...` — `/api/v1/agent/chat`, `/api/v1/agent/stream`, `/api/v1/agents`, `/api/v1/servers`; springdoc/swagger-ui already available

---

## Phase 1 — MVP *(required for first paid release)*

### 1. PR workflow — the missing core *(highest-priority blocker)*
- [x] Detect changes after agent run (`git status --porcelain`); skip commit/push/PR if none
- [x] `git add -A` and commit with a generated message (task summary)
- [x] Push working branch to `origin` using the user's GitHub credential
- [x] Open a PR via GitHub REST API (`POST /repos/{owner}/{repo}/pulls`) targeting the base branch
- [x] Surface the PR URL in `AgentInfo` / SSE (`pr_opened` event) and persist it
- [x] Add `PullRequestPublisher` SPI in `agent-core` (no GitHub SDK in `agent-core`); implement in `mcp-integration` (git CLI for push + thin GitHub REST call)
- [x] Handle partial/failed change sets gracefully (log warning, skip PR)

### 2. Per-user BYOK key plumbing *(second-highest blocker)*
- [x] Refactor `OpenAiClient` (previously singleton with key baked in at constructor) so the API key is a per-call parameter on `chat()` and `chatStream()`
- [ ] Apply the same fix to `EmbeddingClient` if per-user long-term memory is offered
- [ ] Store each user's OpenRouter key encrypted server-side (envelope encryption / KMS); never log keys
- [x] Inject the key via `AgentRequest.apiKey` — propagated through `AgentService` → `OpenAiClient`
- [ ] Validate the key cheaply before starting a billable run; surface bad-key errors clearly

### 3. GitHub identity per user *(third-highest blocker)*
- [ ] Create a **GitHub App**; let users install it on repos they want the agent to touch
- [ ] Mint short-lived per-task installation tokens in the control plane
- [ ] Replace `GitRepositoryProvisioner.configureAuth` (currently fragile: `GIT_ASKPASS=sh` + `!echo password=<token>`) with injection of the short-lived token scoped to one clone/task (e.g. `https://x-access-token:<token>@github.com/...` remote), discarded after the task
- [ ] Alternatively support GitHub OAuth user tokens (simpler but broader scope)

### 4. Accounts, authn/authz
- [ ] Delegate identity to GitHub OAuth / Clerk / Auth0 / Supabase (preferred over building password infra)
- [ ] Issue per-user API tokens for the front-end; replace the single shared `agent.api-key` in `SecurityConfig` with per-user token validation and tenant scoping
- [ ] Authorize every `/api/v1/agents/{id}` action against the owning user (currently any caller can GET/DELETE any agent)

### 5. Durable persistence & job model
- [x] Add `RunStore` SPI in `agent-core` with `FileRunStore` implementation (reloads agent state on startup)
- [x] Enforce `maxConcurrent` with a `Semaphore` in `CloudAgentService` (previously declared in `CloudAgentProperties` but not enforced)
- [x] Reload `agent.json` / file records on startup so agent state survives restarts (via `FileRunStore.reload()`)
- [ ] Introduce Postgres for users, subscriptions, agent runs, PR results, usage; replace file-based store
- [ ] Make a run a **durable async job**: enqueue on submit, return an id immediately, execute on a worker; a dropped connection must not kill or orphan a run
- [ ] Add a queue (Postgres-backed, Redis, or SQS) to decouple submission from execution

### 6. Tenant isolation / real sandbox *(security-critical)*
- [ ] Move the agent loop + `run_bash` out of the shared backend JVM — it currently runs in-process, giving one tenant access to all others' files, env vars, and secrets
- [ ] Spawn one **ephemeral sandbox per task** (recommended: Fly.io Machines / Firecracker microVMs); destroy on completion
- [ ] Inject per-task credentials (OpenRouter key, short-lived GitHub token) into the sandbox; never share across tenants
- [ ] Lock down sandbox egress to OpenRouter + GitHub only
- [ ] Enforce per-task resource caps (CPU, memory, disk, wall-clock time) with forced teardown
- [ ] Restrict MCP stdio servers to remote/SSE MCP or run inside the sandbox (they currently spawn host subprocesses)
- [ ] Move secrets from plain env vars (`OPEN_ROUTER_API_KEY`, `GITHUB_TOKEN`) to a secret manager with encryption at rest

### 7. Billing & subscription
- [ ] Integrate Stripe (or equivalent): $5/mo subscription
- [ ] Drive entitlement from Stripe webhooks; gate run submission on active subscription
- [ ] Provide customer portal for cancel/update; handle dunning/past-due → disable runs

### 8. Quotas, rate limits, cost controls
- [x] Per-user concurrent-run cap enforced (via `Semaphore` in `CloudAgentService`, controlled by `agent.cloud.max-concurrent`)
- [ ] Monthly run/minute quota
- [ ] Hard per-run ceiling on sandbox minutes and max repo size (use shallow clones; cap clone depth)
- [ ] Validate/allowlist repo hosts; block runs against arbitrary URLs
- [ ] Scan task inputs for prompt-injection-driven abuse
- [ ] Write audit log per run (who, what repo, what model, outcome, PR)

---

## Phase 2 — Harden & Scale

- [ ] Enforce concurrency limits with a semaphore/queue in the worker layer
- [ ] Make SSE streaming reconnectable — replay last N events on reconnect so a dropped browser connection doesn't lose progress
- [ ] Structured logs with tenant/run ids correlated across control plane and sandbox
- [ ] Metrics (Micrometer/Prometheus) and alerting on failure rate, sandbox exhaustion, queue depth
- [ ] Distributed tracing across the agent loop
- [ ] Full egress lockdown in sandbox images (firewall rules; allow only OpenRouter + GitHub)
- [ ] Namespace long-term Redis memory per user (currently global); or disable it for the cloud product
- [ ] Abuse prevention: free-trial limits, throwaway-account detection, fair-use policy enforcement

---

## Phase 3 — Differentiate

- [ ] CI-feedback loop: agent reads failing checks after PR is opened and pushes fixes (iterate until green)
- [ ] Repo indexing / context: build or integrate a code-search index so the agent understands the full repo before editing
- [ ] Multiple PR revisions per task: allow the user to comment on the PR and have the agent iterate
- [ ] Scheduled / event-triggered runs (e.g. nightly cleanup, on-issue-label)
- [ ] Model recommendations: suggest the best OpenRouter model for a task type / repo size
- [ ] GitLab / Bitbucket support (GitHub-only at launch)

---

## Open questions (must be decided before/during Phase 1)

- [ ] **Identity provider**: build auth, or delegate to GitHub OAuth / Clerk / Auth0 / Supabase?
- [ ] **GitHub integration**: GitHub App (recommended) vs. user OAuth tokens?
- [ ] **Self-host tier?** — removed the Pi/appliance assets; keep as deleted
- [ ] **Agent loop location**: entirely in the sandbox (recommended), or loop in control plane with only tool calls remoted?
- [ ] **OpenRouter key custody**: store encrypted server-side (smoother UX) vs. inject per-run from the front-end (less custody risk)?
- [ ] **Free trial / abuse**: how to prevent throwaway accounts from burning sandbox minutes?
- [ ] **Repo scope**: GitHub-only at launch, or GitLab/Bitbucket from day one?
