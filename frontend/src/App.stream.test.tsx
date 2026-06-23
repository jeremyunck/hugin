// @vitest-environment jsdom

import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";

import { Messages, reduceChatEvent, restorePreferredThread } from "./App";
import type { ChatEvent } from "./services/guildService";
import type { ChatThread } from "./lib/types";

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

describe("chat session stream replay", () => {
  it("projects assistant reasoning into chat and tool calls inline", () => {
    const rebuilt = [
      event({
        id: "evt-1",
        seq: 1,
        type: "assistant_message_started",
        messageId: "assistant-1",
        role: "assistant",
        createdAt: "2026-06-20T00:00:01.000Z"
      }),
      event({
        id: "evt-2",
        seq: 2,
        type: "assistant_reasoning",
        messageId: "assistant-1",
        role: "assistant",
        content: "Thinking...",
        createdAt: "2026-06-20T00:00:02.000Z"
      }),
      event({
        id: "evt-3",
        seq: 3,
        type: "tool_call_started",
        metadata: { callId: "call-1", name: "read_file", args: "{\"path\":\"README.md\"}" },
        createdAt: "2026-06-20T00:00:03.000Z"
      }),
      event({
        id: "evt-4",
        seq: 4,
        type: "tool_call_completed",
        metadata: { callId: "call-1", name: "read_file", result: "# Hugin" },
        createdAt: "2026-06-20T00:00:04.000Z"
      }),
      event({
        id: "evt-5",
        seq: 5,
        type: "assistant_token",
        messageId: "assistant-1",
        role: "assistant",
        content: "Updated the file.",
        createdAt: "2026-06-20T00:00:05.000Z"
      })
    ].reduce(reduceChatEvent, baseThread());

    // The transcript carries the assistant message plus the tool call inline; nothing is diverted to
    // the separate activity projection.
    expect(rebuilt.entries.map((entry) => entry.type)).toEqual(["assistant", "tool"]);
    const assistant = rebuilt.entries[0];
    expect(assistant.type === "assistant" ? assistant.reasoning : "").toBe("Thinking...");
    expect((rebuilt.activities ?? []).some((activity) => activity.type.startsWith("tool_call"))).toBe(false);

    render(<Messages entries={rebuilt.entries} busy={false} listRef={{ current: null }} />);
    expect(screen.getByText("Thinking...")).toBeTruthy();
    expect(screen.getByText("Updated the file.")).toBeTruthy();
    // The tool call now renders inline in the transcript, surfacing the tool name.
    expect(screen.getByText("read_file")).toBeTruthy();
  });

  it("restores the previously active thread from local storage after a reload", () => {
    const storage = new Map<string, string>();
    Object.defineProperty(window, "localStorage", {
      value: {
        getItem: (key: string) => storage.get(key) ?? null,
        setItem: (key: string, value: string) => void storage.set(key, value),
        removeItem: (key: string) => void storage.delete(key)
      },
      configurable: true
    });
    window.localStorage.setItem("hugin-active-thread-v1", JSON.stringify({
      threadId: "thread-2",
      screen: "chat"
    }));

    const older = baseThread();
    const newer = {
      ...baseThread(),
      id: "thread-2",
      kind: "github" as const,
      updatedAt: "2026-06-20T00:00:10.000Z"
    };

    const restored = restorePreferredThread([older, newer]);

    expect(restored?.thread.id).toBe("thread-2");
    expect(restored?.screen).toBe("chat");
  });
});
