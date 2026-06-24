import { describe, expect, it } from "vitest";

import type { ChatThread } from "../lib/types";
import type { ChatEvent } from "../services/guildService";
import { isThreadBusy, reduceChatEvent, reduceChatEvents } from "./chatEventReducer";

function baseThread(): ChatThread {
  return {
    id: "thread-1",
    title: "Test thread",
    kind: "github",
    createdAt: "2026-06-20T00:00:00.000Z",
    updatedAt: "2026-06-20T00:00:00.000Z",
    entries: [],
    activities: [],
    run: null,
    lastSeq: 0
  };
}

function event(overrides: Partial<ChatEvent> & Pick<ChatEvent, "id" | "seq" | "type" | "createdAt">): ChatEvent {
  return {
    id: overrides.id,
    seq: overrides.seq,
    type: overrides.type,
    createdAt: overrides.createdAt,
    messageId: overrides.messageId ?? null,
    runId: overrides.runId ?? "run-1",
    role: overrides.role ?? null,
    content: overrides.content ?? null,
    metadata: overrides.metadata ?? {}
  };
}

const FULL_TURN: ChatEvent[] = [
  event({ id: "e1", seq: 1, type: "user_message_created", messageId: "user-1", content: "Update the README", createdAt: "2026-06-20T00:00:01.000Z" }),
  event({ id: "e2", seq: 2, type: "run_started", createdAt: "2026-06-20T00:00:02.000Z" }),
  event({ id: "e3", seq: 3, type: "assistant_message_started", messageId: "assistant-1", createdAt: "2026-06-20T00:00:03.000Z" }),
  event({ id: "e4", seq: 4, type: "assistant_reasoning", messageId: "assistant-1", content: "Thinking...", createdAt: "2026-06-20T00:00:04.000Z" }),
  event({ id: "e5", seq: 5, type: "tool_call_started", metadata: { callId: "c1", name: "read_file", args: "{\"path\":\"README.md\"}" }, createdAt: "2026-06-20T00:00:05.000Z" }),
  event({ id: "e6", seq: 6, type: "tool_call_completed", metadata: { callId: "c1", name: "read_file", result: "# Hugin" }, createdAt: "2026-06-20T00:00:06.000Z" }),
  event({ id: "e7", seq: 7, type: "assistant_token", messageId: "assistant-1", content: "Done.", createdAt: "2026-06-20T00:00:07.000Z" }),
  event({ id: "e8", seq: 8, type: "assistant_message_completed", messageId: "assistant-1", content: "Done.", createdAt: "2026-06-20T00:00:08.000Z" }),
  event({ id: "e9", seq: 9, type: "run_completed", createdAt: "2026-06-20T00:00:09.000Z" })
];

describe("reduceChatEvent", () => {
  it("does not duplicate a message when the same event is applied twice", () => {
    const userEvent = event({ id: "e1", seq: 1, type: "user_message_created", messageId: "user-1", content: "Hi", createdAt: "2026-06-20T00:00:01.000Z" });
    const once = reduceChatEvent(baseThread(), userEvent);
    const twice = reduceChatEvent(once, userEvent);
    expect(twice).toBe(once); // idempotent no-op
    expect(twice.entries.filter((entry) => entry.type === "user")).toHaveLength(1);
  });

  it("merges streamed assistant deltas into a single assistant message", () => {
    const result = reduceChatEvents(baseThread(), [
      event({ id: "a1", seq: 1, type: "assistant_message_started", messageId: "assistant-1", createdAt: "2026-06-20T00:00:01.000Z" }),
      event({ id: "a2", seq: 2, type: "assistant_token", messageId: "assistant-1", content: "Hel", createdAt: "2026-06-20T00:00:02.000Z" }),
      event({ id: "a3", seq: 3, type: "assistant_token", messageId: "assistant-1", content: "lo ", createdAt: "2026-06-20T00:00:03.000Z" }),
      event({ id: "a4", seq: 4, type: "assistant_token", messageId: "assistant-1", content: "world", createdAt: "2026-06-20T00:00:04.000Z" })
    ]);
    const assistants = result.entries.filter((entry) => entry.type === "assistant");
    expect(assistants).toHaveLength(1);
    expect(assistants[0].type === "assistant" ? assistants[0].content : "").toBe("Hello world");
  });

  it("rebuilds the same messages from a fetched event list", () => {
    const result = reduceChatEvents(baseThread(), FULL_TURN);
    expect(result.entries.map((entry) => entry.type)).toEqual(["user", "assistant", "tool"]);
    const assistant = result.entries[1];
    expect(assistant.type === "assistant" ? assistant.content : "").toBe("Done.");
    expect(assistant.type === "assistant" ? assistant.reasoning : "").toBe("Thinking...");
    expect(assistant.type === "assistant" ? Boolean(assistant.completedAt) : false).toBe(true);
  });

  it("projects tool events inline into the chat transcript, merging start and result", () => {
    const result = reduceChatEvents(baseThread(), FULL_TURN);
    const tools = result.entries.filter((entry) => entry.type === "tool");
    expect(tools).toHaveLength(1);
    const tool = tools[0];
    expect(tool.type === "tool" ? tool.tool.name : "").toBe("read_file");
    expect(tool.type === "tool" ? tool.tool.args : "").toBe("{\"path\":\"README.md\"}");
    expect(tool.type === "tool" ? tool.tool.result : "").toBe("# Hugin");
    expect(tool.type === "tool" ? Boolean(tool.tool.finishedAt) : false).toBe(true);
    // Tool calls no longer leak into the separate activity projection.
    expect((result.activities ?? []).some((activity) => activity.type.startsWith("tool_call"))).toBe(false);
  });

  it("projects a conversation_compacted event as an inline notice", () => {
    const result = reduceChatEvents(baseThread(), [
      event({ id: "c1", seq: 1, type: "conversation_compacted", content: "Conversation compacted.", metadata: { summary: "earlier" }, createdAt: "2026-06-20T00:00:01.000Z" })
    ]);
    const notices = result.entries.filter((entry) => entry.type === "notice");
    expect(notices).toHaveLength(1);
    expect(notices[0].type === "notice" ? notices[0].content : "").toBe("Conversation compacted.");
  });

  it("updates run state on failure without losing messages", () => {
    const failed = reduceChatEvents(baseThread(), [
      event({ id: "e1", seq: 1, type: "user_message_created", messageId: "user-1", content: "Do it", createdAt: "2026-06-20T00:00:01.000Z" }),
      event({ id: "e2", seq: 2, type: "run_started", createdAt: "2026-06-20T00:00:02.000Z" }),
      event({ id: "e3", seq: 3, type: "assistant_message_started", messageId: "assistant-1", createdAt: "2026-06-20T00:00:03.000Z" }),
      event({ id: "e4", seq: 4, type: "assistant_token", messageId: "assistant-1", content: "partial", createdAt: "2026-06-20T00:00:04.000Z" }),
      event({ id: "e5", seq: 5, type: "run_error", metadata: { message: "boom" }, createdAt: "2026-06-20T00:00:05.000Z" })
    ]);
    expect(failed.run?.status).toBe("failed");
    expect(failed.run?.error).toBe("boom");
    // The user message and the partial assistant message survive the failure.
    expect(failed.entries.map((entry) => entry.type)).toEqual(["user", "assistant"]);
    expect(failed.entries[1].type === "assistant" ? failed.entries[1].content : "").toBe("partial");
    expect(isThreadBusy(failed)).toBe(false);
  });

  it("treats a fresh running run as busy but ages out a stale one", () => {
    const running: ChatThread = {
      ...baseThread(),
      run: { id: "run-1", status: "running", startedAt: "2026-06-20T00:00:00.000Z" },
      updatedAt: "2026-06-20T00:10:00.000Z"
    };
    const lastActivity = Date.parse(running.updatedAt);
    // Within the staleness window the run is still considered in-flight.
    expect(isThreadBusy(running, lastActivity + 60_000)).toBe(true);
    // Past the window a run that never terminated stops blocking the composer.
    expect(isThreadBusy(running, lastActivity + 16 * 60 * 1000)).toBe(false);
  });

  it("keeps a cancelling run busy regardless of staleness", () => {
    const cancelling: ChatThread = {
      ...baseThread(),
      run: { id: "run-1", status: "cancelling", startedAt: "2026-06-20T00:00:00.000Z" },
      updatedAt: "2026-06-20T00:00:00.000Z"
    };
    expect(isThreadBusy(cancelling, Date.parse(cancelling.updatedAt) + 60 * 60 * 1000)).toBe(true);
  });

  it("produces an equivalent projection from live SSE events and a fetched list", () => {
    const fromList = reduceChatEvents(baseThread(), FULL_TURN);
    // Simulate live delivery one event at a time, with a duplicate redelivery in the middle.
    let live = baseThread();
    for (const evt of FULL_TURN) {
      live = reduceChatEvent(live, evt);
      if (evt.seq === 5) live = reduceChatEvent(live, evt); // duplicate SSE frame
    }
    expect(live.entries).toEqual(fromList.entries);
    expect(live.activities).toEqual(fromList.activities);
    expect(live.run).toEqual(fromList.run);
    expect(live.lastSeq).toBe(fromList.lastSeq);
  });

  it("projects an approval_required event into a pending approval card and parks the run", () => {
    const result = reduceChatEvents(baseThread(), [
      event({ id: "e1", seq: 1, type: "user_message_created", messageId: "user-1", content: "delete these", createdAt: "2026-06-20T00:00:01.000Z" }),
      event({ id: "e2", seq: 2, type: "run_started", createdAt: "2026-06-20T00:00:02.000Z" }),
      event({
        id: "e3",
        seq: 3,
        type: "approval_required",
        content: "Approval required to move 2 emails to Trash.",
        metadata: {
          kind: "email_delete",
          approvalId: "ap-1",
          items: [
            { id: "m1", from: "a@x.com", subject: "Hi", snippet: "hello there" },
            { id: "m2", from: "b@y.com", subject: "Yo" }
          ]
        },
        createdAt: "2026-06-20T00:00:03.000Z"
      })
    ]);
    const approvals = result.entries.filter((entry) => entry.type === "approval");
    expect(approvals).toHaveLength(1);
    const approval = approvals[0];
    expect(approval.type === "approval" ? approval.status : "").toBe("pending");
    expect(approval.type === "approval" ? approval.approvalId : "").toBe("ap-1");
    expect(approval.type === "approval" ? approval.items.length : 0).toBe(2);
    // The run is parked (stopped) but not finished, so the composer is free.
    expect(result.run?.status).toBe("awaiting_approval");
    expect(isThreadBusy(result)).toBe(false);
    // The approval event drives an inline card, not the separate activity projection.
    expect((result.activities ?? []).some((activity) => activity.type === "approval_required")).toBe(false);
  });

  it("resolves an approval card and completes the run, idempotent under redelivery", () => {
    const required = event({
      id: "e1",
      seq: 1,
      type: "approval_required",
      content: "Approval required to move 1 email to Trash.",
      metadata: { kind: "email_delete", approvalId: "ap-9", items: [{ id: "m1", from: "a@x.com", subject: "Hi" }] },
      createdAt: "2026-06-20T00:00:01.000Z"
    });
    const resolved = event({
      id: "e2",
      seq: 2,
      type: "approval_resolved",
      content: "Moved 1 email to Trash.",
      metadata: { approvalId: "ap-9", decision: "approved", deletedCount: 1 },
      createdAt: "2026-06-20T00:00:02.000Z"
    });
    let thread = reduceChatEvent(reduceChatEvent(baseThread(), required), resolved);
    // Re-applying the resolved event must not create a second card.
    thread = reduceChatEvent(thread, resolved);
    const approvals = thread.entries.filter((entry) => entry.type === "approval");
    expect(approvals).toHaveLength(1);
    const approval = approvals[0];
    expect(approval.type === "approval" ? approval.status : "").toBe("approved");
    expect(approval.type === "approval" ? approval.resultText : "").toBe("Moved 1 email to Trash.");
    expect(thread.run?.status).toBe("completed");
    expect(isThreadBusy(thread)).toBe(false);
  });

  it("reconciles an optimistic pending user draft into the confirmed backend message", () => {
    const withPending: ChatThread = {
      ...baseThread(),
      entries: [
        { id: "pending-1", type: "user", content: "Hello", createdAt: "2026-06-20T00:00:00.500Z", pending: true }
      ]
    };
    const confirmed = reduceChatEvent(
      withPending,
      event({ id: "e1", seq: 1, type: "user_message_created", messageId: "user-1", content: "Hello", createdAt: "2026-06-20T00:00:01.000Z" })
    );
    const users = confirmed.entries.filter((entry) => entry.type === "user");
    expect(users).toHaveLength(1);
    expect(users[0].id).toBe("user-1");
    expect(users[0].type === "user" ? users[0].pending : true).toBeFalsy();
  });
});
