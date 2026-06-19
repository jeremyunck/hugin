import { describe, expect, it, vi, afterEach } from "vitest";

import type { ChatThread } from "../lib/types";
import {
  buildUserEntry,
  createThread,
  recoverThreadAfterDroppedStream,
  type ServerChatMessage
} from "./guildService";

describe("recoverThreadAfterDroppedStream", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("rebuilds the thread from saved history after a dropped stream that already executed a tool", async () => {
    const baseThread: ChatThread = {
      ...createThread("sandbox", { sandboxId: "sandbox-1" }),
      id: "thread-1",
      entries: [buildUserEntry("Please update the file.")]
    };

    const history: ServerChatMessage[] = [
      { role: "user", content: "Please update the file." },
      {
        role: "assistant",
        content: "",
        reasoning_content: null,
        tool_calls: [
          {
            id: "call-1",
            function: {
              name: "edit_file",
              arguments: "{\"path\":\"frontend/src/App.tsx\"}"
            }
          }
        ]
      },
      { role: "tool", tool_call_id: "call-1", content: "Edited frontend/src/App.tsx (1 replacement)." },
      { role: "assistant", content: "The change is in place.", reasoning_content: null }
    ];

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => history
    }));

    const recovered = await recoverThreadAfterDroppedStream("token", baseThread, 0, "Please update the file.");

    expect(recovered).not.toBeNull();
    expect(recovered?.entries.map((entry) => entry.type)).toEqual(["user", "tool", "assistant"]);
    const toolEntry = recovered?.entries.find((entry) => entry.type === "tool");
    expect(toolEntry && toolEntry.type === "tool" ? toolEntry.tool.name : "").toBe("edit_file");
    expect(toolEntry && toolEntry.type === "tool" ? toolEntry.tool.result : "").toContain("Edited frontend/src/App.tsx");
    const assistantEntry = recovered?.entries.at(-1);
    expect(assistantEntry?.type).toBe("assistant");
    expect(assistantEntry?.type === "assistant" ? assistantEntry.content : "").toBe("The change is in place.");
  });
});
