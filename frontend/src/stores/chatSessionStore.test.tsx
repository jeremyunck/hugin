// @vitest-environment jsdom

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";

import type { ChatEvent } from "../services/guildService";
import { useChatSessionStore, type ChatSessionDeps } from "./chatSessionStore";

function event(seq: number, overrides: Partial<ChatEvent> = {}): ChatEvent {
  return {
    id: overrides.id ?? `e${seq}`,
    seq,
    type: overrides.type ?? "assistant_token",
    createdAt: overrides.createdAt ?? `2026-06-20T00:00:0${seq}.000Z`,
    messageId: overrides.messageId ?? "assistant-1",
    runId: overrides.runId ?? "run-1",
    role: overrides.role ?? null,
    content: overrides.content ?? "",
    metadata: overrides.metadata ?? {}
  };
}

type StreamHandlers = Parameters<ChatSessionDeps["openChatEventStream"]>[3];

function makeDeps() {
  const streams: Array<{ sessionId: string; afterSeq: number; handlers: StreamHandlers; closed: boolean }> = [];
  const deps: ChatSessionDeps = {
    sendChatMessage: vi.fn().mockResolvedValue({ sessionId: "thread-1", messageId: "user-1", runId: "run-1", lastSeq: 1 }),
    fetchChatSessionEvents: vi.fn().mockResolvedValue([]),
    cancelChatRun: vi.fn().mockResolvedValue(undefined),
    openChatEventStream: vi.fn((_token, sessionId, afterSeq, handlers) => {
      const record = { sessionId, afterSeq, handlers, closed: false };
      streams.push(record);
      return {
        close() {
          record.closed = true;
        }
      };
    })
  };
  return { deps, streams };
}

describe("useChatSessionStore", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("hydrates a thread from fetched events and dedups live SSE frames", async () => {
    const { deps, streams } = makeDeps();
    (deps.fetchChatSessionEvents as ReturnType<typeof vi.fn>).mockResolvedValue([
      event(1, { type: "user_message_created", messageId: "user-1", content: "Hi" }),
      event(2, { type: "assistant_message_started" }),
      event(3, { type: "assistant_token", content: "Hello" })
    ]);

    const { result } = renderHook(() => useChatSessionStore("token", deps));

    act(() => {
      result.current.switchThread({
        id: "thread-1",
        title: "T",
        kind: "chat",
        createdAt: "2026-06-20T00:00:00.000Z",
        updatedAt: "2026-06-20T00:00:00.000Z",
        entries: [],
        activities: [],
        lastSeq: 0
      });
    });

    await waitFor(() => expect(result.current.activeThread?.entries.length).toBe(2));
    expect(streams).toHaveLength(1);
    // The stream reconnect cursor must equal the highest applied seq.
    expect(streams[0].afterSeq).toBe(3);

    // A duplicate live frame for an already-applied seq must not change the projection.
    const before = result.current.activeThread?.entries;
    act(() => {
      streams[0].handlers.onEvent(event(3, { type: "assistant_token", content: "Hello" }));
    });
    expect(result.current.activeThread?.entries).toBe(before);

    // A new live frame extends the assistant message.
    act(() => {
      streams[0].handlers.onEvent(event(4, { type: "assistant_token", content: " world" }));
    });
    const assistant = result.current.activeThread?.entries.find((entry) => entry.type === "assistant");
    expect(assistant?.type === "assistant" ? assistant.content : "").toBe("Hello world");
  });

  it("disconnects the previous thread's stream when switching threads", async () => {
    const { deps, streams } = makeDeps();
    const { result } = renderHook(() => useChatSessionStore("token", deps));

    const make = (id: string) => ({
      id,
      title: id,
      kind: "chat" as const,
      createdAt: "2026-06-20T00:00:00.000Z",
      updatedAt: "2026-06-20T00:00:00.000Z",
      entries: [],
      activities: [],
      lastSeq: 0
    });

    act(() => result.current.switchThread(make("thread-1")));
    await waitFor(() => expect(streams.length).toBe(1));
    act(() => result.current.switchThread(make("thread-2")));
    await waitFor(() => expect(streams.length).toBe(2));

    expect(streams[0].closed).toBe(true);
    expect(streams[1].closed).toBe(false);
    expect(result.current.activeThreadId).toBe("thread-2");
  });

  it("sends a message optimistically and marks the run queued before the server responds", async () => {
    const { deps } = makeDeps();
    let resolveSend: () => void = () => {};
    (deps.sendChatMessage as ReturnType<typeof vi.fn>).mockImplementation(
      () => new Promise<{ sessionId: string; messageId: string; runId: string; lastSeq: number }>((resolve) => {
        resolveSend = () => resolve({ sessionId: "thread-1", messageId: "user-1", runId: "run-1", lastSeq: 1 });
      })
    );
    const { result } = renderHook(() => useChatSessionStore("token", deps));
    act(() => result.current.switchThread({
      id: "thread-1",
      title: "T",
      kind: "chat",
      createdAt: "2026-06-20T00:00:00.000Z",
      updatedAt: "2026-06-20T00:00:00.000Z",
      entries: [],
      activities: [],
      lastSeq: 0
    }));

    let sendPromise: Promise<void> = Promise.resolve();
    act(() => {
      sendPromise = result.current.sendMessage("thread-1", { content: "Hello", mode: "CHAT", title: "T" });
    });

    // Optimistic pending draft is visible and the run is queued (busy) immediately.
    expect(result.current.activeThread?.entries.some((entry) => entry.type === "user" && entry.pending)).toBe(true);
    expect(result.current.activeThread?.run?.status).toBe("queued");
    expect(result.current.activeBusy).toBe(true);

    await act(async () => {
      resolveSend();
      await sendPromise;
    });
    expect(deps.sendChatMessage).toHaveBeenCalledTimes(1);
  });

  it("cancels the active run, marking it cancelling and re-syncing from the event log", async () => {
    const { deps } = makeDeps();
    // The cancel triggers a re-hydrate; return the terminal run_error so the composer re-enables.
    (deps.fetchChatSessionEvents as ReturnType<typeof vi.fn>).mockResolvedValue([
      event(1, { type: "user_message_created", messageId: "user-1", content: "Hi" }),
      event(2, { type: "run_started" }),
      event(3, { type: "run_error", metadata: { message: "Run stopped." } })
    ]);
    const { result } = renderHook(() => useChatSessionStore("token", deps));
    act(() => result.current.switchThread({
      id: "thread-1",
      title: "T",
      kind: "chat",
      createdAt: "2026-06-20T00:00:00.000Z",
      updatedAt: "2026-06-20T00:00:00.000Z",
      entries: [],
      activities: [],
      lastSeq: 0,
      run: { id: "run-1", status: "running", startedAt: "2026-06-20T00:00:00.000Z" }
    }));

    await act(async () => {
      await result.current.cancelRun("thread-1");
    });

    expect(deps.cancelChatRun).toHaveBeenCalledWith("token", "thread-1");
    // After the terminal event is folded in, the run is failed and the thread is no longer busy.
    expect(result.current.activeThread?.run?.status).toBe("failed");
    expect(result.current.activeBusy).toBe(false);
  });
});
