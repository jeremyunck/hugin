---
name: hugin-sandbox-screenshots
description: Use when you must bring up the full Hugin stack from scratch in a fresh/headless environment (e.g. Claude Code on the web, a CI box, or any container with no Docker daemon), seed a login, and capture browser screenshots of a sandbox chat driving the agent. Covers native Postgres/Redis startup, building the bundled jar, the no-Docker "host-fallback" sandbox trick, an OpenRouter + gpt-oss-120b config, and a Playwright headless-shell capture flow with the exact UI selectors.
---

# Hugin Sandbox Screenshots (from-scratch local run)

Use this when you have a clean checkout and need to actually *run* Hugin and
photograph the UI — including a **sandbox chat** — rather than just verify a
frontend tweak. This differs from `hugin-local-dev` (assumes an installed macOS
runtime + LaunchAgents) and `hugin-ui-screenshots` (assumes a running Vite dev
server). Here we build the bundled jar and serve everything from one process on
`:8080`, no Docker daemon required.

## When the host-fallback trick matters

Hugin's "New sandbox" button calls `POST /api/sandboxes`, which normally starts a
Docker container. In a nested sandbox / cloud session there is usually **no
Docker daemon**. `DockerSandboxManager` has a built-in escape hatch: if the
docker *binary itself* cannot be launched, it transparently creates a
**host-fallback sandbox** that runs the agent's tools in a per-session workspace
directory on the host. Trigger it by pointing `SANDBOX_DOCKER_BIN` at a binary
that does not exist (see config below). The UI then shows a real sandbox chat
(`sandbox` badge, `~/sandbox/<id>` file tree) and `run_bash`/`write_file` work.

> Do not just leave `SANDBOX_DOCKER_BIN=docker`: with the real CLI present but no
> daemon, `docker run` returns a connection error (non-zero exit), which Hugin
> treats as a hard failure — sandbox creation fails instead of falling back.

## Preconditions

- Run from the repo root. Tools needed: Java 21, Maven, Node, PostgreSQL, Redis.
- An OpenRouter key in `OPEN_ROUTER_API_KEY` (the agent needs it to actually reply).
- Outbound network to `openrouter.ai`, `repo1.maven.org`, `registry.npmjs.org`,
  and `cdn.playwright.dev` (for the browser download).

## 1. Start PostgreSQL and Redis

```bash
pg_ctlcluster 16 main start            # or: service postgresql start
redis-server --daemonize yes --port 6379

# Create the database/role the app expects, then grant schema rights (PG15+ needs this).
sudo -u postgres psql -v ON_ERROR_STOP=1 <<'SQL'
CREATE USER hugin WITH PASSWORD 'hugin';
CREATE DATABASE hugin OWNER hugin;
GRANT ALL PRIVILEGES ON DATABASE hugin TO hugin;
SQL
sudo -u postgres psql -d hugin -v ON_ERROR_STOP=1 <<'SQL'
GRANT ALL ON SCHEMA public TO hugin;
ALTER SCHEMA public OWNER TO hugin;
SQL
```

`schema.sql` is applied automatically on boot (`spring.sql.init.mode=always`),
so no manual table creation is needed.

## 2. Build the bundled jar

The default-active `frontend` Maven profile runs `npm install` + `npm run build`
and copies the React bundle into the backend, so one command builds everything:

```bash
mvn -q -DskipTests package
# -> backend/target/hugin-backend-0.0.1-SNAPSHOT.jar  (Spring Boot serves UI + API on :8080)
```

## 3. Run with a seeded login + host-fallback sandbox

`auth.bootstrap` seeds a real `app_users` row to log in with. Setting
`AUTH_BOOTSTRAP_PASSWORD` creates the user on startup — that IS the test user in
the DB. (Alternatively set `AUTH_TEST_USER_USERNAME`/`AUTH_TEST_USER_PASSWORD`
to seed the dedicated screenshot account per `AGENTS.md`.)

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/hugin
export SPRING_DATASOURCE_USERNAME=hugin
export SPRING_DATASOURCE_PASSWORD=hugin
export LLM_PROVIDER=openrouter
export LLM_MODEL=openai/gpt-oss-120b          # auto-enabled via the model catalog fallback
export OPEN_ROUTER_API_KEY="$OPEN_ROUTER_API_KEY"
export SANDBOX_ENABLED=true
export SANDBOX_DOCKER_BIN=docker-unavailable-fallback   # nonexistent -> host-fallback sandbox
export AGENT_HOME=/home/user/.hugin
export AUTH_BOOTSTRAP_USERNAME=testuser
export AUTH_BOOTSTRAP_PASSWORD='Test1234!'    # seeds the DB login
export MEMORY_ENABLED=false

java -jar backend/target/hugin-backend-0.0.1-SNAPSHOT.jar > /tmp/hugin.log 2>&1 &
```

Wait for readiness (health is `DOWN` until Redis is up, hence step 1):

```bash
for i in $(seq 1 60); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health)" = "200" ] && break
  sleep 1
done
curl -s http://localhost:8080/actuator/health   # {"status":"UP"}
```

## 4. Smoke-test via API before driving the browser

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"testuser","password":"Test1234!"}' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')

# Host-fallback sandbox: containerName starts with "host-fallback-".
curl -s -X POST http://localhost:8080/api/sandboxes \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'

# Confirm the default model is enabled.
curl -s "http://localhost:8080/api/models?enabledOnly=true" -H "Authorization: Bearer $TOKEN"
```

## 5. Install a headless browser for Playwright

The Microsoft download host is usually blocked; Playwright automatically falls
back to `cdn.playwright.dev`, which is allowlisted. The chromium **headless
shell** is enough for `chromium.launch({ headless: true })`.

```bash
export PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright
npx --yes playwright@1.56.1 install chromium     # downloads headless shell from cdn.playwright.dev

mkdir -p /tmp/shots && cd /tmp/shots
npm init -y >/dev/null && npm install playwright@1.56.1 >/dev/null
```

## 6. Capture the flow

Use the companion script and run it (it drives login -> New sandbox -> prompt ->
waits for the final answer -> screenshots each step):

```bash
cd /tmp/shots
cp <repo>/docs/skills/hugin-sandbox-screenshots/files/capture.mjs .
PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright node capture.mjs
```

Key facts the script relies on (see [files/capture.mjs](files/capture.mjs)):

- The app is a **390px-wide centered column**; use a `1400x900` viewport
  (`deviceScaleFactor: 2`) to match the look of the repo's reference screenshots.
- Sign in via placeholders `Enter your username` / `Enter your password`, button
  text `Sign in`.
- Open the menu via `aria-label="Open menu"`, then click
  `button.menu-item:has-text("New sandbox")`.
- The composer is `input[placeholder="Message Hugin…"]` (note the `…` glyph) and
  the send button is `[aria-label="Send message"]`.
- A finished run re-enables the composer; the final answer renders in
  `.message-row-assistant .assistant-response`. Tool calls render as
  `details.tool-event` — collapse them (`removeAttribute('open')`) before the
  final shot so the answer is unobstructed.
- Wait for the assistant text to be non-empty **and stable** across a few polls;
  do not key off the busy flag alone (SSE completion can lag the server).

## 7. Verify (do not trust exit codes)

Open each PNG and confirm it shows the intended state. Cross-check the agent
actually did the work server-side:

```bash
find "$AGENT_HOME/sandboxes" -name hello.py -exec cat {} \;   # the file it created
PGPASSWORD=hugin psql -h localhost -U hugin -d hugin -c \
  "select status, mode from agent_runs order by started_at desc limit 1;"  # completed | SANDBOX
```

## Gotchas

- **Health `DOWN` with Redis errors**: start Redis (step 1). `MEMORY_ENABLED=false`
  still leaves the Redis health indicator active.
- **Composer disabled / "No enabled models"**: the catalog syncs from OpenRouter
  on first `/api/models` call and auto-enables `llm.model`. A missing/invalid
  `OPEN_ROUTER_API_KEY` breaks the sync and leaves nothing enabled.
- **In-session file tree shows `(empty)`**: the side file tree may not re-render
  after the run even though the file exists. Verify on disk (step 7) rather than
  relying on the panel.
- **Sandbox creation fails instead of falling back**: you left a real `docker`
  binary in `SANDBOX_DOCKER_BIN` with no daemon. Point it at a nonexistent name.
```
