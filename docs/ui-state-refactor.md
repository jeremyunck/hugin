# UI State Refactor — Implementation Note

## Current Architecture (Pre-Refactor)

### State Ownership

All state lives in `frontend/src/App.tsx` (~2658 lines) as React `useState` + `useRef` hooks:

| State | Type | Owner | Persisted |
|---|---|---|---|
| `state` | `AppState` (`{ threads: ChatThread[] }`) | `useState` in App.tsx | localStorage `hugin-minimal-ui-state-v1` |
| `thread` | `ChatThread` (current active thread) | `useState` in App.tsx | localStorage `hugin-active-thread-v1` (id only) |
| `busy` | `boolean` (global busy flag) | `useState` in App.tsx | No |
| `models` | `ModelOption[]` | `useState` in App.tsx | No |
| `files` | `FileNode[]` (sandbox file tree) | `useState` in App.tsx | No |
| `streamRef` | `{ threadId: string; close: () => void }` | `useRef` in App.tsx | No |
| Various UI state | booleans, strings, arrays | `useState` in App.tsx | Some (active thread restore) |

There is no `stores/` directory. No Zustand, Redux, or context-based state management.

### Where Messages Are Created

**Frontend (optimistic):**
- `guildService.ts`: `buildUserEntry()`, `buildAssistantEntry()`, `buildToolEvent()` all generate IDs client-side via `uid()`.
- These functions are used in `rebuildThreadFromHistory()` but NOT used during live streaming — the frontend relies entirely on backend events for live rendering.

**Backend:**
- `ChatSessionService.runAgent()` generates events as the agent executes:
  - `run_started` / `run_completed` / `run_error`
  - `assistant_message_started`, `assistant_token`, `assistant_reasoning`, `assistant_message_completed`, `assistant_message_error`
  - `tool_call_started`, `tool_call_completed` (from `AgentStreamListener` callbacks)
- Each event is persisted with a monotonically increasing `seq` via `repository.insertEvent()`.

### Where Chat Events Are Hydrated

1. **On thread load**: `hydrateThreadFromEvents()` calls `fetchChatSessionEvents(token, threadId)` which hits `GET /api/chat/sessions/{id}/events`. Events are reduced through `reduceChatEvent()`.

2. **On stream reconnect**: The SSE stream first replays persisted events (events with `seq > afterSeq`), then subscribes to live events via the broker.

3. **Fallback recovery**: `recoverThreadAfterDroppedStream()` polls `GET /api/agent/history?sessionId=...` and rebuilds via `rebuildThreadFromHistory()`.

### SSE / Stream Reconnect

- `openChatEventStream()` in `guildService.ts` opens a fetch-based SSE reader.
- Reconnect: infinite loop with exponential backoff (1s-5s). Reconnects with `afterSeq` set to the highest seq seen.
- Abort controller for cleanup.
- `ensureStream()` in App.tsx manages a single stream ref. If a stream already exists for the same thread, it is kept; otherwise the old stream is closed and a new one opened.
- Visibility change handler re-fetches events and restarts the stream on tab focus.

### localStorage Usage

| Key | Content | Purpose |
|---|---|---|
| `hugin-minimal-ui-state-v1` | Full `AppState` (all threads, all entries, all activities) | Persist entire UI state across refreshes |
| `hugin-active-thread-v1` | `{ threadId, screen }` | Restore the last active thread on reload |
| `hugin-auth-session-v1` (sessionStorage) | Auth token + user info | Login session |

### Tool/Activity Output in Main Chat

When `reduceChatEvent()` processes `tool_call_started` / `tool_call_completed`:

1. A `ChatEntry` of `type: "tool"` is pushed into `entries[]`. This is rendered by the `Messages` component as expandable `<details>` elements showing args + result.
2. A `ChatActivity` is simultaneously pushed into `activities[]`. This is rendered by the `ActivityPanel` component.

So tool calls appear **twice**: once inline in the message stream (as collapsible tool entries) and once in a separate Activity panel at the bottom.

### Likely Causes of Desync / Duplicates / Disappearing Messages

1. **localStorage as source of truth**: `saveAppState()` persists the entire thread list including all entries. On refresh, `loadAppState()` restores this. If the backend persisted different data (e.g., a message was created but stream dropped), the local state can diverge from the server state. The `hydrateThreadFromEvents` tries to reconcile but the logic in the `!events.length` branch explicitly keeps local state if events are empty.

2. **Seq-based deduplication**: `reduceChatThread` skips events where `thread.lastSeq >= event.seq`. If two threads share seq values (they shouldn't since seq is global per session), or if events are applied out of order, messages can be lost or duplicated.

3. **No event ID deduplication**: There is no check for `event.id` already being present. Only `seq` is checked. If the same event arrives with the same seq through two paths (e.g., initial fetch + live stream), it would only be applied once due to seq ordering, but this is fragile.

4. **Global `busy` flag**: Single boolean for all threads. Switching threads while a run is active will misrepresent the busy state. The `applyEventsToThread` callback derives busy from "any assistant entry without completedAt", which is fragile when switching threads.

5. **Race between hydrate and stream**: After `send()` calls `hydrateThreadFromEvents()` then `ensureStream()`, there's a window where:
   - The stream emits events for the current run
   - The stream also replays the same events the hydrate just fetched
   - The seq check prevents duplicates only if seq ordering is strict

6. **Visibility change double-fetch**: The `handleVisibility` effect both re-fetches events AND restarts the stream. If the stream is still alive, the restart causes a replay of already-seen events.

7. **Tool entries in main chat**: Tool entries mixed into the message list create visual noise and duplicate the data shown in ActivityPanel. This is by design but causes the "agent/tool output leaking into the main chat" symptom.

8. **Thread sync issues**: `thread` (current thread useState) and `state.threads` (the thread list) are dual state variables. The `upsertThread` callback and `useEffect` at line 1840 sync `thread` into `state.threads`, but there's no guarantee of consistency between the two during rapid event processing.

9. **`messageCount` excludes tool entries** (line 234), but tool entries still render in the chat. This inconsistency means history shows fewer messages than actually rendered.

## Backend Event Lifecycle

The full event flow for a single user message:

1. `POST /api/chat/sessions/{id}/messages` → returns `{ sessionId, messageId, runId, lastSeq }`
2. Backend creates `user_message_created` event (seq N)
3. Backend creates `run_started` event (seq N+1)
4. Backend creates `assistant_message_started` event (seq N+2)
5. Backend streams `assistant_reasoning` tokens (seq N+3 ...)
6. For each tool: `tool_call_started` then `tool_call_completed` events
7. Backend streams `assistant_token` deltas
8. Backend creates `assistant_message_completed` event
9. Backend creates `run_completed` event

All events are persisted with monotonically increasing `seq` values. The frontend tracks `lastSeq` per thread and uses `afterSeq` to fetch only newer events.

## Backend API Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/chat/sessions/{sessionId}/messages` | Send message, returns `{ sessionId, messageId, runId, lastSeq }` |
| `GET` | `/api/chat/sessions/{sessionId}/events?afterSeq=N` | Fetch persisted events |
| `GET` | `/api/chat/sessions/{sessionId}/stream?afterSeq=N` | SSE stream (replay then live) |
| `GET` | `/api/agent/history?sessionId=...` | Full conversation history (used for recovery fallback) |

## Current Component Tree

```
App
├── LoginScreen (when not authenticated)
├── [MainChat] (screen === "chat" | "purechat")
│   ├── AppHeader
│   ├── FileTree (only for sandbox/github kind)
│   ├── Greeting (thread.entries.length === 0)
│   ├── Messages (thread.entries — renders user + assistant + tool)
│   ├── ActivityPanel (thread.activities)
│   └── InputBar
├── HistoryScreen
├── IntegrationsScreen
├── SettingsScreen / ModelSettings
├── RepoSetupScreen (GitHub repo setup)
├── AgentThreadsScreen
├── MenuOverlay
└── SettingsScreen
```

## Key Functions to Extract

| Function | Current Location | New Home |
|---|---|---|
| `reduceChatEvent()` | App.tsx (exported) | `chatEventReducer.ts` |
| `hydrateThreadFromEvents()` | App.tsx | `chatSessionStore.ts` |
| `ensureStream()` / stream lifecycle | App.tsx | `chatSessionStore.ts` |
| `upsertThread()` | App.tsx | `chatSessionStore.ts` |
| `applyEventsToThread()` | App.tsx | `chatSessionStore.ts` |
| `saveAppState()/loadAppState()` | guildService.ts | Quarantined |
| `openChatEventStream()` | guildService.ts | Keep but connect through store |
| `recoverThreadAfterDroppedStream()` | guildService.ts | Keep or move to store |

## Backend Event Types (from ChatSessionService)

| Event Type | Has messageId | Has runId | content | Has metadata |
|---|---|---|---|---|
| `user_message_created` | Yes | Yes | User text | `attachments` |
| `assistant_message_started` | Yes | Yes | "" | - |
| `assistant_token` | Yes | Yes | Delta text | - |
| `assistant_reasoning` | Yes | Yes | Delta text | - |
| `assistant_message_completed` | Yes | Yes | Full text | - |
| `assistant_message_error` | Yes | Yes | Error message | `message` |
| `tool_call_started` | No | Yes | - | `callId`, `name`, `args` |
| `tool_call_completed` | No | Yes | - | `callId`, `name`, `result` |
| `run_started` | No | Yes | - | - |
| `run_completed` | No | Yes | - | - |
| `run_error` | No | Yes | - | `message` |