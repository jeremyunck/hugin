import { describe, expect, it } from "vitest";

import { parseFileDiff } from "./FileDiffEntry";
import type { StreamToolEvent } from "../../lib/types";

function tool(partial: Partial<StreamToolEvent>): StreamToolEvent {
  return {
    id: "t1",
    name: "edit_file",
    args: "{}",
    result: "",
    startedAt: "2026-06-26T00:00:00Z",
    ...partial
  };
}

describe("parseFileDiff", () => {
  it("renders write_file content as all additions", () => {
    const diff = parseFileDiff(
      tool({ name: "write_file", args: JSON.stringify({ path: "src/a.ts", content: "one\ntwo\n" }) })
    );
    expect(diff).not.toBeNull();
    expect(diff?.mode).toBe("write");
    expect(diff?.path).toBe("src/a.ts");
    expect(diff?.additions).toBe(2);
    expect(diff?.deletions).toBe(0);
    expect(diff?.lines).toEqual([
      { kind: "add", text: "one" },
      { kind: "add", text: "two" }
    ]);
  });

  it("renders edit_file as removed lines then added lines", () => {
    const diff = parseFileDiff(
      tool({
        name: "edit_file",
        args: JSON.stringify({ path: "src/b.ts", old_string: "old", new_string: "new\nnew2" })
      })
    );
    expect(diff?.mode).toBe("edit");
    expect(diff?.additions).toBe(2);
    expect(diff?.deletions).toBe(1);
    expect(diff?.lines).toEqual([
      { kind: "del", text: "old" },
      { kind: "add", text: "new" },
      { kind: "add", text: "new2" }
    ]);
  });

  it("treats a deletion (empty new_string) as removed lines only", () => {
    const diff = parseFileDiff(
      tool({ args: JSON.stringify({ path: "x", old_string: "gone", new_string: "" }) })
    );
    expect(diff?.additions).toBe(0);
    expect(diff?.deletions).toBe(1);
  });

  it("returns null for malformed args so the caller can fall back", () => {
    expect(parseFileDiff(tool({ args: "not json" }))).toBeNull();
  });

  it("returns null when the path is missing", () => {
    expect(parseFileDiff(tool({ args: JSON.stringify({ content: "x" }) }))).toBeNull();
  });
});
