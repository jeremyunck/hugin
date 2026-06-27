import type {
  AppState,
  ChatAttachment,
  ChatEntry,
  ChatKind,
  ChatMessage,
  ChatThread,
  StreamToolEvent
} from "../lib/types";
import {
  apiFetch,
  delay,
  errorFromResponse,
  nowIso,
  uid,
  type ChatEvent
} from "./apiClient";

const APP_STORAGE_KEY = "bouw-minimal-ui-state-v1";

// Compiled out of a normal build; only the mock harness sets this. The JSON-data endpoints route to
// fixtures via `apiFetch`/`mockApiFetch`; the raw-fetch mutations and the SSE stream below have no
// backend in mock mode, so they short-circuit to inert no-ops.
const MOCK_MODE = import.meta.env.VITE_BOUW_MOCK_MODE === "true";

export type ChatSessionSummary = {
  id: string;
  title: string;
  mode: string;
  createdAt: string;
  updatedAt: string;
};

type ChatEventsResponse = {
  sessionId: string;
  events: ChatEvent[];
};

export type SendChatMessageOptions = {
  content: string;
  mode: "CHAT" | "AGENT" | "GITHUB";
  title: string;
  attachments?: ChatAttachment[];
  model?: string;
  reasoningEffort?: string;
  sandboxId?: string;
  maxToolCalls?: number | null;
  requestTimeoutSeconds?: number | null;
  researchModel?: string | null;
};

export type SendChatMessageResponse = {
  sessionId: string;
  messageId: string;
  runId: string;
  lastSeq: number;
};

export type ChatEventStreamHandlers = {
  onEvent: (event: ChatEvent) => void;
  onStatus?: (status: "connecting" | "open" | "reconnecting" | "closed" | "error") => void;
  onError?: (error: Error) => void;
};

export function createEmptyState(): AppState {
  return { threads: [] };
}

/**
 * @deprecated Quarantined after the Phase 2 refactor. The server event log is the source of truth
 * for chat transcripts; the UI now persists only lightweight thread metadata via the chat session
 * store ({@link ../stores/chatSessionStore}). These full-transcript helpers remain only for the
 * one-time localStorage migration and must not be used as state of record.
 */
export function loadAppState(): AppState {
  if (typeof window === "undefined") return createEmptyState();
  const raw = window.localStorage.getItem(APP_STORAGE_KEY);
  if (!raw) return createEmptyState();

  try {
    const parsed = JSON.parse(raw) as Partial<AppState>;
    return {
      threads: Array.isArray(parsed.threads) ? parsed.threads : []
    };
  } catch {
    return createEmptyState();
  }
}

/** @deprecated See {@link loadAppState}. Retained only for migration; not the state of record. */
export function saveAppState(state: AppState) {
  if (typeof window === "undefined") return;
  const sanitized: AppState = {
    ...state,
    threads: state.threads.map((thread) => ({
      ...thread,
      entries: thread.entries.map((entry) =>
        entry.type !== "user" || !entry.attachments?.length
          ? entry
          : {
              ...entry,
              // Avoid exhausting localStorage with base64 payloads; active chat state keeps the
              // live preview, while persisted history retains only metadata.
              attachments: entry.attachments.map((attachment) => ({
                name: attachment.name,
                mimeType: attachment.mimeType,
                size: attachment.size
              }))
            }
      )
    }))
  };
  window.localStorage.setItem(APP_STORAGE_KEY, JSON.stringify(sanitized));
}

export async function sendChatMessage(
  token: string,
  sessionId: string,
  options: SendChatMessageOptions
): Promise<SendChatMessageResponse> {
  return apiFetch<SendChatMessageResponse>(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
    {
      method: "POST",
      body: JSON.stringify({
        content: options.content,
        mode: options.mode,
        title: options.title,
        ...(options.attachments?.length ? { attachments: options.attachments } : {}),
        ...(options.model ? { model: options.model } : {}),
        ...(options.reasoningEffort ? { reasoningEffort: options.reasoningEffort } : {}),
        ...(options.sandboxId ? { sandboxId: options.sandboxId } : {}),
        ...(options.maxToolCalls != null ? { maxToolCalls: options.maxToolCalls } : {}),
        ...(options.requestTimeoutSeconds != null ? { requestTimeoutSeconds: options.requestTimeoutSeconds } : {}),
        ...(options.researchModel ? { researchModel: options.researchModel } : {})
      })
    },
    token
  );
}

export async function cancelChatRun(token: string, sessionId: string): Promise<void> {
  if (MOCK_MODE) return;
  // The cancel endpoint returns 202/204 with no body, so use a raw fetch rather than apiFetch
  // (which parses JSON). Any active run is terminated server-side; the resulting run_error arrives
  // over the event stream and re-enables the composer.
  const response = await fetch(`/api/chat/sessions/${encodeURIComponent(sessionId)}/cancel`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` }
  });
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`);
  }
}

export async function resolveChatApproval(
  token: string,
  sessionId: string,
  approvalId: string,
  decision: "approve" | "decline"
): Promise<void> {
  if (MOCK_MODE) return;
  // The approval endpoint returns 202 with no body; the resulting approval_resolved/run_completed
  // events arrive over the event stream (and on the next hydrate) to update the card and composer.
  const response = await fetch(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/approvals/${encodeURIComponent(approvalId)}`,
    {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
      body: JSON.stringify({ decision })
    }
  );
  if (!response.ok) {
    throw await errorFromResponse(response);
  }
}

export async function fetchChatSessionEvents(token: string, sessionId: string, afterSeq = 0): Promise<ChatEvent[]> {
  const query = afterSeq > 0 ? `?afterSeq=${afterSeq}` : "";
  const response = await apiFetch<ChatEventsResponse>(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/events${query}`,
    {},
    token
  );
  return response.events;
}

type CreateThreadOptions = {
  sandboxId?: string;
  repoFullName?: string;
  repoName?: string;
  branchName?: string;
};

export function createThread(kind: ChatKind = "chat", options: CreateThreadOptions = {}): ChatThread {
  const createdAt = nowIso();
  const title = kind === "agent"
    ? "New agent"
    : kind === "github"
    ? `${options.repoFullName ?? "Project"}${options.branchName ? ` (${options.branchName})` : ""}`
    : "New chat";
  return {
    id: uid("thread"),
    title,
    kind,
    sandboxId: options.sandboxId,
    repoFullName: options.repoFullName,
    repoName: options.repoName,
    branchName: options.branchName,
    modelId: undefined,
    reasoningEffort: undefined,
    createdAt,
    updatedAt: createdAt,
    entries: [],
    activities: [],
    lastSeq: 0,
    connectionStatus: "idle"
  };
}

export function getThreadTitle(prompt: string) {
  const normalized = prompt.trim().replace(/\s+/g, " ");
  if (!normalized) return "New chat";
  const words = normalized.split(/\s+/);
  const MAX_WORDS = 10;
  if (words.length <= MAX_WORDS) return normalized;
  return words.slice(0, MAX_WORDS).join(" ") + "…";
}

export function addThread(state: AppState, thread: ChatThread): AppState {
  return {
    ...state,
    threads: [thread, ...state.threads]
  };
}

export function updateThread(state: AppState, threadId: string, updater: (thread: ChatThread) => ChatThread): AppState {
  return {
    ...state,
    threads: state.threads.map((thread) => (thread.id === threadId ? updater(thread) : thread))
  };
}

export function removeThread(state: AppState, threadId: string): AppState {
  return {
    ...state,
    threads: state.threads.filter((thread) => thread.id !== threadId)
  };
}

export function buildUserEntry(content: string, attachments?: ChatAttachment[]): ChatEntry {
  return {
    id: uid("user"),
    type: "user",
    content,
    ...(attachments?.length ? { attachments } : {}),
    createdAt: nowIso()
  };
}

export function buildAssistantEntry(): Extract<ChatEntry, { type: "assistant" }> {
  return {
    id: uid("assistant"),
    type: "assistant",
    content: "",
    reasoning: "",
    createdAt: nowIso()
  };
}

function buildToolEvent(name: string, args: string): StreamToolEvent {
  return {
    id: uid("tool"),
    name,
    args,
    result: "",
    startedAt: nowIso()
  };
}

export function appendEntries(
  state: AppState,
  threadId: string,
  entries: ChatEntry[],
  titleOverride?: string
): AppState {
  return updateThread(state, threadId, (thread) => ({
    ...thread,
    title: titleOverride ?? thread.title,
    updatedAt: nowIso(),
    entries: [...thread.entries, ...entries]
  }));
}

export function appendAssistantDelta(state: AppState, threadId: string, assistantId: string, delta: string): AppState {
  return updateThread(state, threadId, (thread) => ({
    ...thread,
    updatedAt: nowIso(),
    entries: thread.entries.map((entry) =>
      entry.type === "assistant" && entry.id === assistantId
        ? { ...entry, content: `${entry.content}${delta}` }
        : entry
    )
  }));
}

export function appendReasoningDelta(state: AppState, threadId: string, assistantId: string, delta: string): AppState {
  return updateThread(state, threadId, (thread) => ({
    ...thread,
    updatedAt: nowIso(),
    entries: thread.entries.map((entry) =>
      entry.type === "assistant" && entry.id === assistantId
        ? { ...entry, reasoning: `${entry.reasoning}${delta}` }
        : entry
    )
  }));
}

export function appendToolCall(state: AppState, threadId: string, name: string, args: string): AppState {
  return appendEntries(state, threadId, [
    {
      id: uid("entry-tool"),
      type: "tool",
      tool: buildToolEvent(name, args),
      createdAt: nowIso()
    }
  ]);
}

export function attachToolResult(state: AppState, threadId: string, name: string, result: string): AppState {
  return updateThread(state, threadId, (thread) => {
    let updated = false;
    return {
      ...thread,
      updatedAt: nowIso(),
      entries: thread.entries.map((entry) => {
        if (updated || entry.type !== "tool" || entry.tool.name !== name || entry.tool.finishedAt) return entry;
        updated = true;
        return {
          ...entry,
          tool: {
            ...entry.tool,
            result,
            finishedAt: nowIso()
          }
        };
      })
    };
  });
}

export function completeAssistantEntry(state: AppState, threadId: string, assistantId: string): AppState {
  return updateThread(state, threadId, (thread) => ({
    ...thread,
    updatedAt: nowIso(),
    entries: thread.entries.map((entry) =>
      entry.type === "assistant" && entry.id === assistantId
        ? { ...entry, completedAt: nowIso() }
        : entry
    )
  }));
}

export type ServerChatMessage = {
  role: "user" | "assistant" | "system" | "tool";
  content: string;
  attachments?: ChatAttachment[];
  reasoning_content?: string | null;
  tool_calls?: Array<{
    id?: string | null;
    function?: {
      name?: string | null;
      arguments?: string | null;
    } | null;
  }> | null;
  tool_call_id?: string | null;
};

async function fetchConversationHistory(token: string, sessionId: string): Promise<ServerChatMessage[]> {
  return apiFetch<ServerChatMessage[]>(`/api/agent/history?sessionId=${encodeURIComponent(sessionId)}`, {}, token);
}

function hasRecoveredTurn(history: ServerChatMessage[], priorMessageCount: number, prompt: string): boolean {
  let lastUserIndex = -1;
  for (let index = history.length - 1; index >= 0; index -= 1) {
    const message = history[index];
    if (message.role === "user" && (message.content ?? "") === prompt) {
      lastUserIndex = index;
      break;
    }
  }
  if (lastUserIndex < priorMessageCount || lastUserIndex === -1) {
    return false;
  }
  const tail = history.slice(lastUserIndex + 1);
  if (!tail.length) {
    return false;
  }
  return tail.some((message) => {
    if (message.role === "tool") {
      return true;
    }
    if (message.role !== "assistant") {
      return false;
    }
    return Boolean(
      (message.content ?? "").trim()
      || (message.reasoning_content ?? "").trim()
      || (message.tool_calls?.length ?? 0) > 0
    );
  });
}

export function rebuildThreadFromHistory(thread: ChatThread, history: ServerChatMessage[]): ChatThread {
  const remote = history.filter((message) =>
    message.role === "user" || message.role === "assistant" || message.role === "tool");
  if (!remote.length) {
    return thread;
  }
  const entries: ChatEntry[] = [];
  const toolEntriesByCallId = new Map<string, number>();

  for (const message of remote) {
    if (message.role === "user") {
      entries.push(buildUserEntry(message.content ?? "", message.attachments));
      continue;
    }

    if (message.role === "assistant") {
      const hasVisibleAssistantContent = Boolean((message.content ?? "").trim() || (message.reasoning_content ?? "").trim());
      if (hasVisibleAssistantContent) {
        entries.push({
          ...buildAssistantEntry(),
          content: message.content ?? "",
          reasoning: message.reasoning_content ?? "",
          completedAt: nowIso()
        });
      }
      for (const toolCall of message.tool_calls ?? []) {
        const toolEntry: ChatEntry = {
          id: uid("entry-tool"),
          type: "tool",
          tool: {
            id: uid("tool"),
            callId: toolCall.id ?? undefined,
            name: toolCall.function?.name ?? "tool",
            args: toolCall.function?.arguments ?? "",
            result: "",
            startedAt: nowIso()
          },
          createdAt: nowIso()
        };
        entries.push(toolEntry);
        if (toolCall.id) {
          toolEntriesByCallId.set(toolCall.id, entries.length - 1);
        }
      }
      continue;
    }

    const byCallId = message.tool_call_id ? toolEntriesByCallId.get(message.tool_call_id) : undefined;
    const matchIndex = byCallId ?? [...entries].reverse().findIndex((entry) =>
      entry.type === "tool" && !entry.tool.finishedAt);
    const entryIndex = byCallId ?? (matchIndex === -1 ? -1 : entries.length - 1 - matchIndex);
    if (entryIndex >= 0 && entries[entryIndex]?.type === "tool") {
      const entry = entries[entryIndex] as Extract<ChatEntry, { type: "tool" }>;
      entries[entryIndex] = {
        ...entry,
        tool: {
          ...entry.tool,
          result: message.content ?? "",
          finishedAt: nowIso()
        }
      };
    }
  }

  return {
    ...thread,
    updatedAt: nowIso(),
    entries
  };
}

export async function recoverThreadAfterDroppedStream(
  token: string,
  thread: ChatThread,
  priorMessageCount: number,
  prompt: string
): Promise<ChatThread | null> {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const history = await fetchConversationHistory(token, thread.id);
      if (hasRecoveredTurn(history, priorMessageCount, prompt)) {
        return rebuildThreadFromHistory(thread, history);
      }
    } catch {
      // Ignore transient polling failures and keep trying until the deadline.
    }
    await delay(1000);
  }
  return null;
}

export function buildPriorMessages(thread: ChatThread): ChatMessage[] {
  const messages: ChatMessage[] = [];
  for (const entry of thread.entries) {
    if (entry.type === "user") {
      messages.push({
        role: "user" as const,
        content: entry.content,
        attachments: entry.attachments
      });
      continue;
    }
    if (entry.type === "assistant") {
      messages.push({
        role: "assistant" as const,
        content: entry.content,
        reasoning_content: entry.reasoning || undefined
      });
    }
  }
  return messages;
}

export async function syncThreadHistory(token: string, thread: ChatThread): Promise<ChatThread> {
  const history = await fetchConversationHistory(token, thread.id);
  return rebuildThreadFromHistory(thread, history);
}

/** Forgets the server-side conversation memory for a session. Best-effort; ignores a missing session. */
export async function deleteThreadHistory(token: string, sessionId: string): Promise<void> {
  if (MOCK_MODE) return;
  await fetch(`/api/agent/history?sessionId=${encodeURIComponent(sessionId)}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` }
  });
}

/** Lists all chat sessions for the authenticated user, ordered by most recent first. */
export async function fetchChatSessions(token: string): Promise<ChatSessionSummary[]> {
  if (MOCK_MODE) return [];
  return apiFetch<ChatSessionSummary[]>(`/api/chat/sessions`, {}, token);
}

function parseChatSseEvent(rawEvent: string): ChatEvent | null {
  const lines = rawEvent
    .split(/\r?\n/)
    .map((line) => line.trimEnd())
    .filter(Boolean);

  if (!lines.length) return null;

  let eventName = "";
  const dataLines: string[] = [];

  for (const line of lines) {
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trim());
    }
  }

  if (eventName !== "chat_event" || !dataLines.length) return null;
  return JSON.parse(dataLines.join("\n")) as ChatEvent;
}

/**
 * Opens the chat session SSE stream. The stream replays persisted events after {@code afterSeq} and
 * then delivers live events; it reconnects with the highest applied seq so a dropped connection only
 * pulls the missing tail. Disconnects never lose history — the backend run continues server-side.
 */
export function openChatEventStream(
  token: string,
  sessionId: string,
  initialAfterSeq: number,
  handlers: ChatEventStreamHandlers
) {
  if (MOCK_MODE) {
    // No live backend in mock mode: the full transcript is delivered up-front by the hydrate path,
    // so the "stream" just reports an open connection and never emits.
    handlers.onStatus?.("open");
    return {
      close() {
        handlers.onStatus?.("closed");
      }
    };
  }

  const controller = new AbortController();
  let afterSeq = initialAfterSeq;
  let closed = false;

  const run = async () => {
    let attempt = 0;
    while (!closed) {
      handlers.onStatus?.(attempt === 0 ? "connecting" : "reconnecting");
      try {
        const response = await fetch(
          `/api/chat/sessions/${encodeURIComponent(sessionId)}/stream?afterSeq=${encodeURIComponent(String(afterSeq))}`,
          {
            method: "GET",
            headers: {
              Accept: "text/event-stream",
              Authorization: `Bearer ${token}`
            },
            signal: controller.signal
          }
        );
        if (!response.ok) {
          throw await errorFromResponse(response);
        }
        if (!response.body) {
          throw new Error("Stream body was not available.");
        }

        handlers.onStatus?.("open");
        attempt = 0;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (!closed) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const parts = buffer.split("\n\n");
          buffer = parts.pop() ?? "";
          for (const rawEvent of parts) {
            const event = parseChatSseEvent(rawEvent);
            if (!event) continue;
            afterSeq = Math.max(afterSeq, event.seq);
            handlers.onEvent(event);
          }
        }

        if (closed) {
          return;
        }
      } catch (error) {
        if (closed || controller.signal.aborted) {
          return;
        }
        handlers.onError?.(error instanceof Error ? error : new Error("Chat stream failed."));
      }

      attempt += 1;
      await delay(Math.min(5000, 1000 * attempt));
    }
  };

  void run();
  return {
    close() {
      closed = true;
      controller.abort();
      handlers.onStatus?.("closed");
    }
  };
}
