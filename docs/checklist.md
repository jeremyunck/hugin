# Cloud Agent Platform — Prioritized Build Checklist

Derived from `cloud-platform-audit.md`. Items are ordered by priority: Phase 0 (prep) → Phase 1 (MVP blockers, ordered by criticality) → Phase 2 (hardening) → Phase 3 (differentiation).

---

## Phase 0 — Repo Cleanup *(low risk, do first)*

- [ ] Remove `agent-terminal` module — delete module dir, remove from parent `pom.xml`, prune `README`/`CLAUDE.md`/`install.sh`/`scripts/` references
- [ ] Tighten CORS — replace `allowedOriginPatterns("*")` + `allowCredentials(true)` in `SecurityConfig.corsConfigurationSource` with explicit front-end origins
- [ ] Authenticate `/api/servers/**` — currently fully unauthenticated; gate it or remove from public surface
- [ ] Re-scope or retire the Raspberry Pi appliance assets (`docs/raspberry-pi-install-plan.md`, `install.sh`, `scripts/mcp-agent*`, systemd unit) — keep only if a self-host tier is intended
- [ ] Document the target API (`/api/v1/...`) with springdoc/OpenAPI; version the existing endpoints

---

## Phase 1 — MVP *(required for first paid release)*

### 1. PR workflow — the missing core *(highest-priority blocker)*
- [ ] Detect changes after agent run (`git status --porcelain`); mark `DONE` with "no changes" if none
- [ ] `git add -A` and commit with a generated message (task summary + co-author trailer)
- [ ] Push working branch to `origin` using the user's GitHub credential
- [ ] Open a PR via GitHub REST API (`POST /repos/{owner}/{repo}/pulls`) targeting the base branch
- [ ] Surface the PR URL in `AgentInfo` / SSE (`pr_opened` event) and persist it
- [ ] Add `PullRequestPublisher` SPI in `agent-core` (no GitHub SDK in `agent-core`); implement in `mcp-integration` (git CLI for push + thin GitHub REST call)
- [ ] Handle partial/failed change sets gracefully (draft PR or no PR)

### 2. Per-user BYOK key plumbing *(second-highest blocker)*
- [ ] Refactor `OpenAiClient` (currently a singleton with the key baked in at `OpenAiClient.java:87-88`) so the API key is a per-call parameter or short-lived client per task
- [ ] Apply the same fix to `EmbeddingClient` if per-user long-term memory is offered
- [ ] Store each user's OpenRouter key encrypted server-side (envelope encryption / KMS); never log keys
- [ ] Inject the key into the sandbox/loop from server-side context — never from the browser request body
- [ ] Validate the key cheaply before starting a billable run; surface bad-key errors clearly

### 3. GitHub identity per user *(third-highest blocker)*
- [ ] Create a **GitHub App**; let users install it on repos they want the agent to touch
- [ ] Mint short-lived per-task installation tokens in the control plane
- [ ] Replace `GitRepositoryProvisioner.configureAuth` (currently fragile: `GIT_ASKPASS=sh` + `!echo password=<token>`) with injection of the short-lived token scoped to one clone/task (e.g. `https://x-access-token:<token>@github.com/...` remote), discarded after the task
- [ ] Alternatively support GitHub OAuth user tokens (simpler but broader scope)

### 4. Accounts, authn/authz
- [ ] Delegate identity to GitHub OAuth / Clerk / Auth0 / Supabase (preferred over building password infra)
- [ ] Issue per-user API tokens for the front-end; replace the single shared `agent.api-key` in `SecurityConfig` with per-user token validation and tenant scoping
- [ ] Authorize every `/api/agents/{id}` action against the owning user (currently any caller can GET/DELETE any agent)

### 5. Durable persistence & job model
- [ ] Introduce Postgres for users, subscriptions, agent runs, PR results, usage; replace the in-memory `ConcurrentHashMap` and `agent.json` files
- [ ] Make a run a **durable async job**: enqueue on submit, return an id immediately, execute on a worker; a dropped connection must not kill or orphan a run
- [ ] Add a queue (Postgres-backed, Redis, or SQS) to decouple submission from execution and enforce `maxConcurrent` (currently declared in `CloudAgentProperties` but not enforced — no semaphore/queue)
- [ ] Reload `agent.json` / DB records on startup so agent state survives restarts

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
- [ ] Per-user concurrent-run cap and monthly run/minute quota
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
- [ ] **Self-host tier?** Keep the Pi/appliance path as a free tier (vs. OpenHands), or drop it?
- [ ] **Agent loop location**: entirely in the sandbox (recommended), or loop in control plane with only tool calls remoted?
- [ ] **OpenRouter key custody**: store encrypted server-side (smoother UX) vs. inject per-run from the front-end (less custody risk)?
- [ ] **Free trial / abuse**: how to prevent throwaway accounts from burning sandbox minutes?
- [ ] **Repo scope**: GitHub-only at launch, or GitLab/Bitbucket from day one?
