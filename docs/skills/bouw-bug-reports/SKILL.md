---
name: bouw-bug-reports
description: Use when working in this repo and a user refers to a freshly reported bug or a file under `bug-reports/`. Covers locating the newest report, separating the original user request from the actual failure, and extracting concrete logs, stack traces, and repro clues that matter for the fix.
---

# Bouw Bug Reports

Use this workflow when the user says things like "look at the bug I just reported" or points at `bug-reports/`.

## Goal

Bug reports in this repo are diagnostic bundles, not concise issue summaries. Read them to find:

- the exact session and screen involved
- the real runtime failure or regression
- the smallest set of code paths and logs worth investigating

Do not assume the first user prompt in the report is the bug.

## Find The Right Report

List the saved reports and start with the newest file:

```bash
rg --files bug-reports
ls -lt bug-reports
```

If the user says "just reported", prefer the newest timestamped file under `bug-reports/YYYY-MM-DD/`.

## Read In Layers

Read the report in this order:

1. Header
2. `Client Context`
3. `Client Thread Snapshot`
4. `Server Conversation History`
5. `Runtime Logs`

This keeps you from anchoring on the wrong section.

## What Each Section Tells You

### Header

Use it to identify:

- generation time
- session id
- workspace root
- saved report path

### Client Context

Use it for:

- active screen
- URL
- browser or device
- whether the UI was busy

This is often enough to tell whether the bug is mobile-only, auth-related, or tied to a specific screen.

### Client Thread Snapshot

Treat this as intent, not proof of failure.

- The user prompt explains what the user asked for.
- The assistant reply shows what the agent started doing.
- Neither one necessarily explains why the run failed.

### Server Conversation History

If present, use it to see whether the backend and client agree on the conversation state. Empty history can itself be a clue.

### Runtime Logs

This is usually where the real bug is.

Search for:

```bash
rg -n "(Exception|Error|failed|500|404|TypeError|ReferenceError|NoClassDefFoundError|IllegalStateException|Connection reset|Could not send SSE event)" bug-reports/<path>.txt
```

Prioritize the first concrete application error near the time of failure, not the longest stack trace.

## Extraction Rules

When summarizing a bug report for implementation work, pull out only:

- the failing endpoint, screen, or job
- the triggering action
- the first meaningful exception or log line
- any follow-on errors caused by that first failure
- the likely source files or subsystems involved

Ignore or de-prioritize:

- giant repeated framework stack traces
- later shutdown-hook noise
- missing optional tools unless the error path depends on them
- the model conversation unless it directly caused the failure

## Common Pattern In This Repo

One recurring pattern is a secondary failure after the client disconnects from an SSE stream.

Typical signal:

- a log line about failing to flush or send an SSE event
- followed by a framework exception while trying to write a normal JSON error body onto an event-stream response

In that case, the client disconnect is usually the primary event and the serializer error is secondary noise.

## Good Output

After reading a report, produce a short diagnostic summary like:

- what the user was trying to do
- what actually failed
- where the first reliable failure appears
- what code path should be inspected next

If you can, cite the report file path and the specific error text you relied on.

## Follow-Up Work

Once you have the signal:

1. inspect the implicated controller, service, or frontend screen
2. add or update a regression test that matches the failure mode
3. only then make the fix

Do not start patching from the user prompt alone when the report contains stronger evidence.
