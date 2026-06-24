import { useCallback, useEffect, useMemo, useReducer, useRef } from "react";

import type { ChatAttachment, ChatRun, ChatThread, ConnectionStatus } from "../lib/types";
import {
  cancelChatRun as defaultCancelRun,
  fetchChatSessionEvents as defaultFetchEvents,
  openChatEventStream as defaultOpenStream,
  resolveChatApproval as defaultResolveApproval,
  sendChatMessage as defaultSendMessage,
  type ChatEvent
} from "../services/guildService";
import { isThreadBusy, reduceChatEvents } from "./chatEventReducer";

/**
 * The chat session store is the single owner of the chat engine: the thread projections, the active
 * thread, per-thread run state, and the SSE stream lifecycle. It deliberately knows nothing about
 * screens, sandboxes, models, or integrations — those stay in the App shell. The server event log is
 * the source of truth; localStorage only holds lightweight metadata for the History list and the
 * last-active thread so a browser refresh can re-open and re-hydrate from the backend.
 */

export type ChatSessionDeps = {
  sendChatMessage: typeof defaultSendMessage;
  fetchChatSessionEvents: typeof defaultFetchEvents;
  openChatEventStream: typeof defaultOpenStream;
  cancelChatRun: typeof defaultCancelRun;
  resolveChatApproval: typeof defaultResolveApproval;
};

const DEFAULT_DEPS: ChatSessionDeps = {
  sendChatMessage: defaultSendMessage,
  fetchChatSessionEvents: defaultFetchEvents,
  openChatEventStream: defaultOpenStream,
  cancelChatRun: defaultCancelRun,
  resolveChatApproval: defaultResolveApproval
};

const THREAD_INDEX_KEY = "hugin-ui-thread-index-v1";
const LEGACY_STATE_KEY = "hugin-minimal-ui-state-v1";
const ACTIVE_THREAD_KEY = "hugin-active-thread-v1";

type ThreadMeta = Pick<
  ChatThread,
  | "id"
  | "title"
  | "kind"
  | "sandboxId"
  | "repoFullName"
  | "repoName"
  | "branchName"
  | "modelId"
  | "reasoningEffort"
  | "createdAt"
  | "updatedAt"
  | "hasHistory"
>;

function toMeta(thread: ChatThread): ThreadMeta {
  return {
    id: thread.id,
    title: thread.title,
    kind: thread.kind,
    sandboxId: thread.sandboxId,
    repoFullName: thread.repoFullName,
    repoName: thread.repoName,
    branchName: thread.branchName,
    modelId: thread.modelId,
    reasoningEffort: thread.reasoningEffort,
    createdAt: thread.createdAt,
    updatedAt: thread.updatedAt,
    hasHistory: true
  };
}

function metaToThread(meta: ThreadMeta): ChatThread {
  return {
    ...meta,
    entries: [],
    activities: [],
    lastSeq: 0,
    run: null,
    connectionStatus: "idle",
    hasHistory: true
  };
}

function loadThreadIndex(): ThreadMeta[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = window.localStorage.getItem(THREAD_INDEX_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as ThreadMeta[];
      return Array.isArray(parsed) ? parsed : [];
    }
    // One-time migration from the legacy full-transcript blob: keep only metadata, drop transcripts.
    const legacy = window.localStorage.getItem(LEGACY_STATE_KEY);
    if (legacy) {
      const parsed = JSON.parse(legacy) as { threads?: ChatThread[] };
      const threads = Array.isArray(parsed.threads) ? parsed.threads : [];
      return threads
        .filter((thread) => thread.entries?.length || (thread.lastSeq ?? 0) > 0)
        .map(toMeta);
    }
  } catch {
    // Corrupt storage should never block startup; fall through to an empty index.
  }
  return [];
}

function saveThreadIndex(threads: ChatThread[]) {
  if (typeof window === "undefined") return;
  try {
    const index = threads.filter(isHistoryThread).map(toMeta);
    window.localStorage.setItem(THREAD_INDEX_KEY, JSON.stringify(index));
  } catch {
    // Ignore quota / serialization failures; the server remains the source of truth.
  }
}

function loadActiveThreadId(): string | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(ACTIVE_THREAD_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { threadId?: string };
    return parsed.threadId ?? null;
  } catch {
    return null;
  }
}

export function saveActiveThreadId(threadId: string | null) {
  if (typeof window === "undefined") return;
  try {
    if (!threadId) {
      window.localStorage.removeItem(ACTIVE_THREAD_KEY);
    } else {
      window.localStorage.setItem(ACTIVE_THREAD_KEY, JSON.stringify({ threadId }));
    }
  } catch {
    // Ignore storage failures; restore is best-effort.
  }
}

export function isHistoryThread(thread: ChatThread): boolean {
  return Boolean(thread.hasHistory) || thread.entries.length > 0 || (thread.lastSeq ?? 0) > 0;
}

type StoreState = {
  byId: Record<string, ChatThread>;
  activeId: string | null;
};

type StoreAction =
  | { type: "init"; threads: ChatThread[]; activeId: string | null }
  | { type: "upsert"; thread: ChatThread }
  | { type: "patch"; id: string; patch: Partial<ChatThread> }
  | { type: "remove"; id: string }
  | { type: "setActive"; id: string }
  | { type: "apply"; id: string; events: ChatEvent[]; replace?: boolean }
  | { type: "setConnection"; id: string; status: ConnectionStatus }
  | { type: "setRun"; id: string; run: ChatRun | null }
  | { type: "addPendingUser"; id: string; entry: { id: string; content: string; attachments?: ChatAttachment[]; createdAt: string } }
  | { type: "clear" };

function storeReducer(state: StoreState, action: StoreAction): StoreState {
  switch (action.type) {
    case "init": {
      const byId: Record<string, ChatThread> = {};
      for (const thread of action.threads) byId[thread.id] = thread;
      return { byId, activeId: action.activeId };
    }
    case "upsert":
      return { ...state, byId: { ...state.byId, [action.thread.id]: action.thread } };
    case "patch": {
      const current = state.byId[action.id];
      if (!current) return state;
      return { ...state, byId: { ...state.byId, [action.id]: { ...current, ...action.patch } } };
    }
    case "remove": {
      const byId = { ...state.byId };
      delete byId[action.id];
      const activeId = state.activeId === action.id ? null : state.activeId;
      return { byId, activeId };
    }
    case "setActive":
      return { ...state, activeId: action.id };
    case "apply": {
      const current = state.byId[action.id];
      if (!current) return state;
      const next = reduceChatEvents(current, action.events, { replace: action.replace });
      if (next === current) return state;
      return { ...state, byId: { ...state.byId, [action.id]: next } };
    }
    case "setConnection": {
      const current = state.byId[action.id];
      if (!current || current.connectionStatus === action.status) return state;
      return { ...state, byId: { ...state.byId, [action.id]: { ...current, connectionStatus: action.status } } };
    }
    case "setRun": {
      const current = state.byId[action.id];
      if (!current) return state;
      return { ...state, byId: { ...state.byId, [action.id]: { ...current, run: action.run } } };
    }
    case "addPendingUser": {
      const current = state.byId[action.id];
      if (!current) return state;
      const entries = [
        ...current.entries,
        {
          id: action.entry.id,
          type: "user" as const,
          content: action.entry.content,
          ...(action.entry.attachments?.length ? { attachments: action.entry.attachments } : {}),
          createdAt: action.entry.createdAt,
          pending: true
        }
      ];
      return { ...state, byId: { ...state.byId, [action.id]: { ...current, entries } } };
    }
    case "clear":
      return { byId: {}, activeId: null };
    default:
      return state;
  }
}

export type SendMessageInput = {
  content: string;
  mode: "CHAT" | "AGENT" | "GITHUB";
  title: string;
  attachments?: ChatAttachment[];
  model?: string;
  reasoningEffort?: string;
  sandboxId?: string;
  maxToolCalls?: number | null;
  requestTimeoutSeconds?: number | null;
};

export type ChatSessionStore = {
  threads: ChatThread[];
  historyThreads: ChatThread[];
  activeThread: ChatThread | null;
  activeThreadId: string | null;
  activeBusy: boolean;
  connectionStatus: ConnectionStatus;
  setThread: (thread: ChatThread) => void;
  patchThread: (id: string, patch: Partial<ChatThread>) => void;
  removeThread: (id: string) => void;
  switchThread: (thread: ChatThread) => void;
  hydrateSession: (threadId: string, options?: { replace?: boolean; afterSeq?: number }) => Promise<number>;
  connectStream: (threadId: string, options?: { force?: boolean; afterSeq?: number }) => void;
  disconnectStream: (threadId: string) => void;
  applyChatEvent: (threadId: string, event: ChatEvent) => void;
  refreshThread: (threadId: string) => Promise<void>;
  sendMessage: (threadId: string, input: SendMessageInput) => Promise<void>;
  markRunCancelling: (threadId: string) => void;
  cancelRun: (threadId: string) => Promise<void>;
  resolveApproval: (threadId: string, approvalId: string, decision: "approve" | "decline") => Promise<void>;
  clearAll: () => void;
};

function uid(prefix: string) {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
  }
  return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
}

/**
 * Owns chat threads and the SSE lifecycle. `token` gates all network work; when absent the store is
 * inert. `deps` is injectable so tests can drive the store with fakes.
 */
export function useChatSessionStore(
  token: string | null,
  deps: ChatSessionDeps = DEFAULT_DEPS
): ChatSessionStore {
  const [state, dispatch] = useReducer(storeReducer, undefined, () => {
    const metas = loadThreadIndex();
    const threads = metas.map(metaToThread);
    const activeId = loadActiveThreadId();
    return {
      byId: Object.fromEntries(threads.map((thread) => [thread.id, thread])),
      activeId: activeId && threads.some((thread) => thread.id === activeId) ? activeId : null
    } satisfies StoreState;
  });

  const stateRef = useRef(state);
  stateRef.current = state;
  const tokenRef = useRef(token);
  tokenRef.current = token;
  const depsRef = useRef(deps);
  depsRef.current = deps;
  const streamsRef = useRef(new Map<string, { close: () => void }>());

  // Persist lightweight thread metadata whenever the set of history threads changes.
  useEffect(() => {
    saveThreadIndex(Object.values(state.byId));
  }, [state.byId]);

  const applyChatEvent = useCallback((threadId: string, event: ChatEvent) => {
    dispatch({ type: "apply", id: threadId, events: [event] });
  }, []);

  const disconnectStream = useCallback((threadId: string) => {
    const handle = streamsRef.current.get(threadId);
    if (handle) {
      handle.close();
      streamsRef.current.delete(threadId);
    }
  }, []);

  const connectStream = useCallback((threadId: string, options?: { force?: boolean; afterSeq?: number }) => {
    const activeToken = tokenRef.current;
    if (!activeToken) return;
    // Switching threads disconnects every stream that is no longer the active one.
    for (const [id, handle] of streamsRef.current) {
      if (id !== threadId) {
        handle.close();
        streamsRef.current.delete(id);
      }
    }
    if (!options?.force && streamsRef.current.has(threadId)) return;
    streamsRef.current.get(threadId)?.close();
    const thread = stateRef.current.byId[threadId];
    // Reconnect from the highest applied seq. Callers that just hydrated pass it explicitly because
    // the dispatched projection may not have committed to the ref yet.
    const afterSeq = options?.afterSeq ?? thread?.lastSeq ?? 0;
    const handle = depsRef.current.openChatEventStream(activeToken, threadId, afterSeq, {
      onEvent: (event) => dispatch({ type: "apply", id: threadId, events: [event] }),
      onStatus: (status) =>
        dispatch({ type: "setConnection", id: threadId, status: status === "closed" ? "idle" : status }),
      onError: () => dispatch({ type: "setConnection", id: threadId, status: "error" })
    });
    streamsRef.current.set(threadId, { close: handle.close });
  }, []);

  /** Fetch events and fold them in. Returns the highest applied seq so callers can seed the stream. */
  const hydrateSession = useCallback(
    async (threadId: string, options?: { replace?: boolean; afterSeq?: number }): Promise<number> => {
      const activeToken = tokenRef.current;
      const current = stateRef.current.byId[threadId];
      const baseSeq = options?.replace ? 0 : current?.lastSeq ?? 0;
      if (!activeToken) return baseSeq;
      const afterSeq = options?.afterSeq ?? 0;
      try {
        const events = await depsRef.current.fetchChatSessionEvents(activeToken, threadId, afterSeq);
        dispatch({ type: "apply", id: threadId, events, replace: options?.replace });
        return events.reduce((max, event) => Math.max(max, event.seq), baseSeq);
      } catch (error) {
        // Leave the projection intact on transient sync failures; the stream will recover.
        console.warn("Failed to hydrate chat thread from events", error);
        return baseSeq;
      }
    },
    []
  );

  const refreshThread = useCallback(
    async (threadId: string) => {
      const thread = stateRef.current.byId[threadId];
      const seq = await hydrateSession(threadId, { afterSeq: thread?.lastSeq ?? 0 });
      connectStream(threadId, { force: true, afterSeq: seq });
    },
    [hydrateSession, connectStream]
  );

  const setThread = useCallback((thread: ChatThread) => {
    dispatch({ type: "upsert", thread });
  }, []);

  const patchThread = useCallback((id: string, patch: Partial<ChatThread>) => {
    dispatch({ type: "patch", id, patch });
  }, []);

  const removeThread = useCallback((id: string) => {
    const handle = streamsRef.current.get(id);
    if (handle) {
      handle.close();
      streamsRef.current.delete(id);
    }
    dispatch({ type: "remove", id });
  }, []);

  const switchThread = useCallback(
    (thread: ChatThread) => {
      if (!stateRef.current.byId[thread.id]) {
        dispatch({ type: "upsert", thread });
      }
      dispatch({ type: "setActive", id: thread.id });
      saveActiveThreadId(thread.id);
      // Rebuild the projection from the server log, then attach a live stream from that cursor.
      void hydrateSession(thread.id, { replace: true }).then((seq) =>
        connectStream(thread.id, { force: true, afterSeq: seq })
      );
    },
    [hydrateSession, connectStream]
  );

  const markRunCancelling = useCallback((threadId: string) => {
    const thread = stateRef.current.byId[threadId];
    if (!thread?.run) return;
    dispatch({ type: "setRun", id: threadId, run: { ...thread.run, status: "cancelling" } });
  }, []);

  /**
   * Stops the active run for a thread. Optimistically flips the run to "cancelling" so the composer
   * shows progress, asks the backend to terminate it, then re-syncs from the event log so the
   * authoritative run_error lands even if the live stream missed it. Tolerant of an already-finished
   * or orphaned run: the backend no-ops the former and force-terminates the latter.
   */
  const cancelRun = useCallback(async (threadId: string) => {
    const activeToken = tokenRef.current;
    if (!activeToken) return;
    markRunCancelling(threadId);
    try {
      await depsRef.current.cancelChatRun(activeToken, threadId);
    } catch (error) {
      console.warn("Failed to cancel chat run", error);
    } finally {
      // Re-hydrate and reconnect regardless: the terminal event is the source of truth for re-enabling
      // the composer, and a failed cancel still needs the projection refreshed to recover.
      await refreshThread(threadId);
    }
  }, [markRunCancelling, refreshThread]);

  const sendMessage = useCallback(async (threadId: string, input: SendMessageInput) => {
    const activeToken = tokenRef.current;
    if (!activeToken) throw new Error("Not authenticated.");
    const thread = stateRef.current.byId[threadId];
    const afterSeq = thread?.lastSeq ?? 0;
    const createdAt = new Date().toISOString();

    // Optimistic pending draft + queued run so the UI reflects the in-flight send immediately.
    if (input.content || input.attachments?.length) {
      dispatch({
        type: "addPendingUser",
        id: threadId,
        entry: {
          id: uid("pending"),
          content: input.content,
          attachments: input.attachments,
          createdAt
        }
      });
    }
    dispatch({ type: "patch", id: threadId, patch: { hasHistory: true } });
    dispatch({ type: "setRun", id: threadId, run: { id: null, status: "queued", startedAt: createdAt } });

    try {
      await depsRef.current.sendChatMessage(activeToken, threadId, {
        content: input.content,
        mode: input.mode,
        title: input.title,
        attachments: input.attachments,
        model: input.model,
        reasoningEffort: input.reasoningEffort,
        sandboxId: input.sandboxId,
        maxToolCalls: input.maxToolCalls,
        requestTimeoutSeconds: input.requestTimeoutSeconds
      });
      const seq = await hydrateSession(threadId, { afterSeq });
      // If a stream is already attached for this thread it keeps running; otherwise open one from the
      // freshly hydrated cursor so the in-flight run streams in.
      if (!streamsRef.current.has(threadId)) {
        connectStream(threadId, { afterSeq: seq });
      }
    } catch (error) {
      const current = stateRef.current.byId[threadId];
      dispatch({
        type: "setRun",
        id: threadId,
        run: {
          id: current?.run?.id ?? null,
          status: "failed",
          completedAt: new Date().toISOString(),
          error: error instanceof Error ? error.message : "The agent request failed."
        }
      });
      throw error;
    }
  }, [hydrateSession, connectStream]);

  /**
   * Sends the user's approve/decline decision for a parked run, then re-syncs from the event log so
   * the authoritative approval_resolved + run_completed land (re-enabling the composer and updating the
   * card) even if the live stream missed them.
   */
  const resolveApproval = useCallback(
    async (threadId: string, approvalId: string, decision: "approve" | "decline") => {
      const activeToken = tokenRef.current;
      if (!activeToken) return;
      try {
        await depsRef.current.resolveChatApproval(activeToken, threadId, approvalId, decision);
      } catch (error) {
        console.warn("Failed to resolve chat approval", error);
      } finally {
        await refreshThread(threadId);
      }
    },
    [refreshThread]
  );

  const clearAll = useCallback(() => {
    for (const handle of streamsRef.current.values()) handle.close();
    streamsRef.current.clear();
    dispatch({ type: "clear" });
    saveActiveThreadId(null);
  }, []);

  // Re-sync and re-attach the active stream when the tab regains focus.
  useEffect(() => {
    if (!token) return;
    const handleVisibility = () => {
      if (document.visibilityState !== "visible") return;
      const activeId = stateRef.current.activeId;
      if (!activeId) return;
      void refreshThread(activeId);
    };
    document.addEventListener("visibilitychange", handleVisibility);
    window.addEventListener("focus", handleVisibility);
    return () => {
      document.removeEventListener("visibilitychange", handleVisibility);
      window.removeEventListener("focus", handleVisibility);
    };
  }, [token, refreshThread]);

  // Tear down every stream on unmount.
  const streams = streamsRef.current;
  useEffect(() => () => {
    for (const handle of streams.values()) handle.close();
    streams.clear();
  }, [streams]);

  const threads = useMemo(
    () => Object.values(state.byId).sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt)),
    [state.byId]
  );
  const historyThreads = useMemo(() => threads.filter(isHistoryThread), [threads]);
  const activeThread = state.activeId ? state.byId[state.activeId] ?? null : null;
  const activeBusy = activeThread ? isThreadBusy(activeThread) : false;
  const connectionStatus = activeThread?.connectionStatus ?? "idle";

  // Memoize the public store so its identity only changes when observable state changes. All actions
  // are stable (useCallback), so unrelated App re-renders (e.g. typing in the composer) don't churn
  // the consumers that depend on the store object.
  return useMemo<ChatSessionStore>(
    () => ({
      threads,
      historyThreads,
      activeThread,
      activeThreadId: state.activeId,
      activeBusy,
      connectionStatus,
      setThread,
      patchThread,
      removeThread,
      switchThread,
      hydrateSession,
      connectStream,
      disconnectStream,
      applyChatEvent,
      refreshThread,
      sendMessage,
      markRunCancelling,
      cancelRun,
      resolveApproval,
      clearAll
    }),
    [
      threads,
      historyThreads,
      activeThread,
      state.activeId,
      activeBusy,
      connectionStatus,
      setThread,
      patchThread,
      removeThread,
      switchThread,
      hydrateSession,
      connectStream,
      disconnectStream,
      applyChatEvent,
      refreshThread,
      sendMessage,
      markRunCancelling,
      cancelRun,
      resolveApproval,
      clearAll
    ]
  );
}
