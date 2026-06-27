---
name: bouw-local-dev
description: Use when working on the Bouw repo and needing to update, build, install, restart, or verify local Bouw service and Discord bot changes. Covers the local jar deployment workflow, LaunchAgent restart steps, version-tool checks, logs, and common sandbox/network caveats.
---

# Bouw Local Development

Use this workflow when changing Bouw code that affects the installed local services.

## Orientation

- Repo root is usually `/Users/jeremyunck/code/bouw/bouw`.
- Installed runtime lives in `~/.bouw`.
- Server jar: `~/.bouw/bin/mcp-integration.jar`.
- Discord bot jar: `~/.bouw/bin/agent-discord.jar`.
- Main logs: `bouw logs` or `~/.bouw/logs/bouw.log`.
- Discord logs: `~/.bouw/logs/discord.log`.
- macOS LaunchAgents: `com.bouw.agent` and `com.bouw.discord`.

## Update From Main

Run:

```bash
git checkout main
git pull
bouw --version
```

If the user specifically asks for latest code, use the approved git commands directly. Report whether the pull fast-forwarded and the resulting version.

## Build And Test

For changes touching the agent core or Discord bot:

```bash
mvn -pl agent-core,agent-discord -am test
```

For installer/package/runtime changes:

```bash
mvn -pl mcp-integration,agent-discord -am package -DskipTests
```

For npm package contents:

```bash
npm --cache /private/tmp/bouw-npm-cache pack --dry-run
```

Use the explicit npm cache because the default `~/.npm` cache may contain root-owned files.

## Install Local Jar Changes

After a successful package build, copy rebuilt jars into the installed runtime:

```bash
cp mcp-integration/target/mcp-integration-0.0.1-SNAPSHOT.jar ~/.bouw/bin/mcp-integration.jar
cp agent-discord/target/agent-discord-0.0.1-SNAPSHOT.jar ~/.bouw/bin/agent-discord.jar
```

These writes are outside the repo and may require escalation.

## Restart Local Services

On macOS:

```bash
launchctl stop com.bouw.agent
launchctl stop com.bouw.discord
launchctl start com.bouw.agent
launchctl start com.bouw.discord
```

Restart both services after changes to shared `agent-core`, because both installed jars may need to reload updated classes. Restart Discord specifically after JDA or button/interactions changes.

## Verify Runtime

Check health:

```bash
curl -sf http://127.0.0.1:8080/actuator/health
```

In the Codex sandbox, localhost access may fail with `Operation not permitted`; retry the health check with escalation when needed.

Check active listeners/processes:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

Confirm Discord jar dependencies when interaction bugs mention JDA classes:

```bash
jar tf ~/.bouw/bin/agent-discord.jar | rg 'BOOT-INF/lib/JDA|InteractionCreateHandler'
```

Expected current JDA dependency is `BOOT-INF/lib/JDA-6.4.1.jar`.

## Version Tool Notes

The server environment may not have `npm`, `node`, `npx`, or `uvx` in PATH. Do not rely on running `npm` inside the server to answer version questions.

Prefer metadata sources:

- `BOUW_VERSION`, `BOUW_REPO_DIR`, `BOUW_LAUNCHER_PATH` from `~/.bouw/bouw.env`.
- `package.json` in the repo or global npm package path resolved from `/opt/homebrew/bin/bouw`.
- `bouw --version` only as an external CLI check from the developer shell.

## Common Findings

- Missing `uvx`/`npx` MCP warnings in logs are often unrelated to Bouw server health.
- A Discord interaction failure with `NoClassDefFoundError` for `InteractionCreateHandler$1` indicates a stale or incomplete Discord runtime jar/process. Rebuild, copy `agent-discord.jar`, and restart `com.bouw.discord`.
- If install/update scripts reference deleted modules or removed launcher assets such as the terminal UI or `scripts/mcp-agent`, fix the script and package manifest before relying on `bouw update`.
