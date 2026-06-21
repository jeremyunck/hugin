import React, { createContext, useContext, useReducer, useRef, useEffect } from "react";
import type { AppState, ChatThread, ChatEvent, ChatActivity } from "../lib/types";
import {
  loadAppState,
  saveAppState,
  openChatEventStream,
  fetchChatSessionEvents,
  sendChatMessage,
} from "../services/guildService";

/**
 * Chat session store – a lightweight replacement for the monolithic state currently held in App.tsx.
 *
 * The store owns the list of threads, the active thread ID, and per‑thread connection/status.
 * It provides actions to upsert threads, apply events, hydrate from the backend, and manage the SSE stream.
 *
 * This is deliberately kept simple (React context + reducer) because the project does not include Zustand.
 */

type StoreState = {
  /** All known threads */
  threads: ChatThread[];
  /** ID of the thread currently shown in the UI */
  activeThreadId: string | null;
  /** Mapping of threadId → connection status for the SSE stream */
  connectionStatus: Record<string, ChatThread["connectionStatus"]>;
};

type Action =
  | { type: "SET_STATE"; payload: StoreState }
  | { type: "UPSERT_THREAD"; thread: ChatThread }
  | { type: "APPLY_EVENTS"; threadId: string; events: ChatEvent[]; replace?: boolean }
  | { type: "SET_ACTIVE_THREAD"; threadId: string }
  | { type: "SET_CONNECTION_STATUS"; threadId: string; status: ChatThread["connectionStatus"] };

const initialState: StoreState = {
  threads: [],
  activeThreadId: null,
  connectionStatus: {},
};

function reducer(state: StoreState, action: Action): StoreState {
  switch (action.type) {
    case "SET_STATE":
      return action.payload;
    case "UPSERT_THREAD": {
      const exists = state.threads.some((t) => t.id === action.thread.id);
      const threads = exists
        ? state.threads.map((t) => (t.id === action.thread.id ? action.thread : t))
        : [action.thread, ...state.threads];
      const newState = { ...state, threads };
      // persist the whole app state (compatible with existing saveAppState)
      saveAppState({ threads: newState.threads });
      return newState;
    }
    case "APPLY_EVENTS": {
      const target = state.threads.find((t) => t.id === action.threadId);
      if (!target) return state;
      const start = action.replace ? { ...target, entries: [], activities: [], lastSeq: 0 } : target;
      const nextThread = action.events.reduce((cur, ev) => reduceChatEvent(cur, ev), start);
      const threads = state.threads.map((t) => (t.id === nextThread.id ? nextThread : t));
      const newState = { ...state, threads };
      saveAppState({ threads: newState.threads });
      return newState;
    }
    case "SET_ACTIVE_THREAD": {
      return { ...state, activeThreadId: action.threadId };
    }
    case "SET_CONNECTION_STATUS": {
      const connectionStatus = { ...state.connectionStatus, [action.threadId]: action.status };
      // also update the thread's own field for compatibility with existing UI code
      const threads = state.threads.map((t) =>
        t.id === action.threadId ? { ...t, connectionStatus: action.status } : t
      );
      const newState = { ...state, threads, connectionStatus };
      saveAppState({ threads: newState.threads });
      return newState;
    }
    default:
      return state;
  }
}

/**
 * The store context – exposed via useChatSessionStore().
 */
const ChatSessionContext = createContext<{
  state: StoreState;
  dispatch: React.Dispatch<Action>;
}>({ state: initialState, dispatch: () => undefined });

export const ChatSessionProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [state, dispatch] = useReducer(reducer, initialState);

  // Load persisted state once on mount.
  useEffect(() => {
    const persisted = loadAppState();
    dispatch({ type: "SET_STATE", payload: { threads: persisted.threads, activeThreadId: null, connectionStatus: {} } });
  }, []);

  // Keep a reference to the current SSE stream per thread.
  const streamRefs = useRef<Record<string, { close: () => void }>>({});

  // Helper to ensure a stream is running for a given thread.
  const ensureStream = (thread: ChatThread, token: string) => {
    if (!token) return;
    const existing = streamRefs.current[thread.id];
    if (existing) return; // already running
    const { close } = openChatEventStream(token, thread.id, thread.lastSeq ?? 0, {
      onEvent: (event) => {
        dispatch({ type: "APPLY_EVENTS", threadId: thread.id, events: [event] });
      },
      onStatus: (status) => {
        dispatch({ type: "SET_CONNECTION_STATUS", threadId: thread.id, status: status as any });
      },
      onError: () => {
        dispatch({ type: "SET_CONNECTION_STATUS", threadId: thread.id, status: "error" });
      },
    });
    streamRefs.current[thread.id] = { close };
  };

  // Helper to close a stream for a thread.
  const disconnectStream = (threadId: string) => {
    const ref = streamRefs.current[threadId];
    if (ref) {
      ref.close();
      delete streamRefs.current[threadId];
    }
    dispatch({ type: "SET_CONNECTION_STATUS", threadId, status: "idle" });
  };

  // Expose utility functions via the context value.
  const value = {
    state,
    dispatch,
    // convenience API used by components in later phases
    upsertThread: (thread: ChatThread) => dispatch({ type: "UPSERT_THREAD", thread }),
    applyEventsToThread: (threadId: string, events: ChatEvent[], replace = false) =>
      dispatch({ type: "APPLY_EVENTS", threadId, events, replace }),
    setActiveThread: (threadId: string) => dispatch({ type: "SET_ACTIVE_THREAD", threadId }),
    ensureStream,
    disconnectStream,
    hydrateThreadFromEvents: async (thread: ChatThread, token: string, afterSeq = 0) => {
      const events = await fetchChatSessionEvents(token, thread.id, afterSeq);
      if (events.length) {
        dispatch({ type: "APPLY_EVENTS", threadId: thread.id, events, replace: afterSeq === 0 });
      }
    },
    sendMessage: async (token: string, thread: ChatThread, content: string, attachment: any) => {
      // thin wrapper around guildService.sendChatMessage – the UI will still handle optimistic UI updates.
      await sendChatMessage(token, thread.id, {
        content,
        mode: thread.kind === "github" ? "GITHUB" : thread.sandboxId ? "SANDBOX" : "CHAT",
        title: thread.title,
        attachments: attachment ? [attachment] : undefined,
        model: undefined,
        reasoningEffort: undefined,
        sandboxId: thread.sandboxId,
      });
      // after sending, we rely on the stream to bring in new events.
    },
  };

  return <ChatSessionContext.Provider value={value}>{children}</ChatSessionContext.Provider>;
};

/** Hook to consume the store. */
export const useChatSessionStore = () => {
  const ctx = useContext(ChatSessionContext);
  if (!ctx) {
    throw new Error("useChatSessionStore must be used within a ChatSessionProvider");
  }
  return ctx;
};

/**
 * Helper pure reducer used for applying events – re-exported for testability.
 * This mirrors the original reduceChatEvent implementation.
 */
export function reduceChatEvent(thread: ChatThread, event: ChatEvent): ChatThread {
  // Inline a minimal copy of the original reducer logic (trimmed for brevity).
  if ((thread.lastSeq ?? 0) >= event.seq) {
    return thread;
  }
  const entries = thread.entries.slice();
  const activities = (thread.activities ?? []).slice();
  const messageId = event.messageId ?? undefined;
  const index = messageId ? entries.findIndex((e) => e.id === messageId) : -1;
  const attachments = Array.isArray(event.metadata?.attachments) ? (event.metadata.attachments as any) : undefined;

  switch (event.type) {
    case "user_message_created": {
      if (messageId && index === -1) {
        entries.push({
          id: messageId,
          type: "user",
          content: event.content ?? "",
          ...(attachments?.length ? { attachments } : {}),
          createdAt: event.createdAt,
        });
      }
      break;
    }
    case "assistant_message_started": {
      if (messageId && index === -1) {
        entries.push({ id: messageId, type: "assistant", content: "", reasoning: "", createdAt: event.createdAt });
      }
      break;
    }
    case "assistant_token": {
      if (messageId) {
        if (index === -1) {
          entries.push({ id: messageId, type: "assistant", content: event.content ?? "", reasoning: "", createdAt: event.createdAt });
        } else if (entries[index]?.type === "assistant") {
          const cur = entries[index] as any;
          entries[index] = { ...cur, content: `${cur.content}${event.content ?? ""}` };
        }
      }
      break;
    }
    case "assistant_reasoning": {
      if (messageId) {
        if (index === -1) {
          entries.push({ id: messageId, type: "assistant", content: "", reasoning: event.content ?? "", createdAt: event.createdAt });
        } else if (entries[index]?.type === "assistant") {
          const cur = entries[index] as any;
          entries[index] = { ...cur, reasoning: `${cur.reasoning}${event.content ?? ""}` };
        }
      }
      break;
    }
    case "assistant_message_completed": {
      if (messageId && index !== -1 && entries[index]?.type === "assistant") {
        const cur = entries[index] as any;
        entries[index] = { ...cur, content: cur.content || event.content || "", completedAt: event.createdAt };
      }
      break;
    }
    case "assistant_message_error": {
      if (messageId) {
        if (index === -1) {
          entries.push({
            id: messageId,
            type: "assistant",
            content: event.content ?? "The run failed.",
            reasoning: "",
            createdAt: event.createdAt,
            completedAt: event.createdAt,
          });
        } else if (entries[index]?.type === "assistant") {
          const cur = entries[index] as any;
          entries[index] = { ...cur, content: cur.content || event.content || "The run failed.", completedAt: event.createdAt };
        }
      }
      break;
    }
    case "tool_call_started": {
      const name = typeof event.metadata?.name === "string" && event.metadata.name ? event.metadata.name : "tool";
      const args = typeof event.metadata?.args === "string" ? event.metadata.args : "";
      const callId = (event.metadata?.callId as string) ?? undefined;
      const toolIndex = callId
        ? entries.findIndex((e) => e.type === "tool" && e.tool.callId === callId)
        : -1;
      if (toolIndex === -1) {
        entries.push({
          id: event.id,
          type: "tool",
          tool: { id: event.id, ...(callId ? { callId } : {}), name, args, result: "", startedAt: event.createdAt },
          createdAt: event.createdAt,
        });
      }
      activities.push({
        id: event.id,
        runId: event.runId ?? undefined,
        type: event.type,
        label: activityLabel(event),
        status: activityStatus(event),
        detail: activityDetail(event),
        createdAt: event.createdAt,
      });
      break;
    }
    case "tool_call_completed": {
      const name = typeof event.metadata?.name === "string" && event.metadata.name ? event.metadata.name : "tool";
      const result = typeof event.metadata?.result === "string" ? event.metadata.result : "";
      const callId = (event.metadata?.callId as string) ?? undefined;
      const toolIndex = findToolCompletionIndex(entries, name, callId);
      if (toolIndex === -1) {
        entries.push({
          id: event.id,
          type: "tool",
          tool: { id: event.id, ...(callId ? { callId } : {}), name, args: "", result, startedAt: event.createdAt, finishedAt: event.createdAt },
          createdAt: event.createdAt,
        });
      } else if (entries[toolIndex]?.type === "tool") {
        const cur = entries[toolIndex] as any;
        entries[toolIndex] = { ...cur, tool: { ...cur.tool, result, finishedAt: event.createdAt } };
      }
      activities.push({
        id: event.id,
        runId: event.runId ?? undefined,
        type: event.type,
        label: activityLabel(event),
        status: activityStatus(event),
        detail: activityDetail(event),
        createdAt: event.createdAt,
      });
      break;
    }
    default: {
      // All other events become activities only.
      activities.push({
        id: event.id,
        runId: event.runId ?? undefined,
        type: event.type,
        label: activityLabel(event),
        status: activityStatus(event),
        detail: activityDetail(event),
        createdAt: event.createdAt,
      });
    }
  }

  return { ...thread, entries, activities, lastSeq: event.seq, updatedAt: event.createdAt };
}

// Helper selectors used inside the reducer – kept identical to the original implementations.
function activityLabel(event: ChatEvent): string {
  const metadata = event.metadata ?? {};
  const name = typeof metadata.name === "string" ? metadata.name : event.type;
  switch (event.type) {
    case "run_started":
      return "Run started";
    case "run_completed":
      return "Run completed";
    case "run_error":
      return "Run failed";
    case "tool_call_started":
      return `Tool started: ${name}`;
    case "tool_call_completed":
      return `Tool completed: ${name}`;
    case "tool_call_error":
      return `Tool failed: ${name}`;
    default:
      return event.type.replaceAll("_", " ");
  }
}

function activityDetail(event: ChatEvent): string | undefined {
  const metadata = event.metadata ?? {};
  if (typeof metadata.result === "string" && metadata.result) return metadata.result;
  if (typeof metadata.args === "string" && metadata.args) return metadata.args;
  if (typeof metadata.message === "string" && metadata.message) return metadata.message;
  return event.content ?? undefined;
}

function activityStatus(event: ChatEvent): ChatActivity["status"] {
  if (event.type.endsWith("_error") || event.type === "run_error") return "error";
  if (event.type.endsWith("_completed") || event.type === "run_completed") return "completed";
  if (event.type.endsWith("_started") || event.type === "run_started") return "running";
  return "info";
}

function findToolCompletionIndex(
  entries: ChatThread["entries"],
  name: string,
  callId?: string
): number {
  if (callId) {
    return entries.findIndex((e) => e.type === "tool" && e.tool.callId === callId);
  }
  for (let i = entries.length - 1; i >= 0; i--) {
    const e = entries[i];
    if (e?.type === "tool" && e.tool.name === name && !e.tool.finishedAt) {
      return i;
    }
  }
  return -1;
}

/** Exported for testing purposes */
export const _test = {
  reducer,
  initialState,
};
