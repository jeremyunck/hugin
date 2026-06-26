import { ChevronRight, FilePlus2, FilePen } from "lucide-react";

import type { StreamToolEvent } from "../../lib/types";

type DiffLine = { kind: "add" | "del"; text: string };

type ParsedDiff = {
  /** Workspace-relative path of the edited file. */
  path: string;
  /** "write" overwrites/creates a whole file; "edit" is a targeted string replacement. */
  mode: "write" | "edit";
  lines: DiffLine[];
  additions: number;
  deletions: number;
};

/** Splits a block of text into lines, dropping a single trailing newline so it doesn't add a blank row. */
function toLines(text: string): string[] {
  if (text === "") return [];
  const normalized = text.endsWith("\n") ? text.slice(0, -1) : text;
  return normalized.split("\n");
}

/**
 * Turns a `write_file` / `edit_file` tool call into the line model a git-style diff renders from.
 * Returns `null` when the arguments can't be understood (e.g. malformed JSON), so the caller can fall
 * back to the generic tool-call card.
 */
export function parseFileDiff(tool: StreamToolEvent): ParsedDiff | null {
  let args: Record<string, unknown>;
  try {
    args = JSON.parse(tool.args || "{}") as Record<string, unknown>;
  } catch {
    return null;
  }
  const path = typeof args.path === "string" ? args.path : "";
  if (!path) return null;

  if (tool.name === "write_file") {
    const content = typeof args.content === "string" ? args.content : "";
    const added = toLines(content);
    return {
      path,
      mode: "write",
      lines: added.map((text) => ({ kind: "add", text })),
      additions: added.length,
      deletions: 0
    };
  }

  if (tool.name === "edit_file") {
    const oldString = typeof args.old_string === "string" ? args.old_string : "";
    const newString = typeof args.new_string === "string" ? args.new_string : "";
    const removed = toLines(oldString);
    const added = toLines(newString);
    return {
      path,
      mode: "edit",
      lines: [
        ...removed.map((text): DiffLine => ({ kind: "del", text })),
        ...added.map((text): DiffLine => ({ kind: "add", text }))
      ],
      additions: added.length,
      deletions: removed.length
    };
  }

  return null;
}

/**
 * Renders a `write_file` / `edit_file` tool call as a git-style diff card so file changes the agent
 * makes in project mode read at a glance: the file path in the header, an added/removed line count, and
 * the changed lines coloured like a unified diff. Falls back to nothing (the caller handles that) when
 * the call can't be parsed into a diff.
 */
export function FileDiffEntry({ tool, diff }: { tool: StreamToolEvent; diff: ParsedDiff }) {
  const pending = !tool.finishedAt;
  const failed = !pending && !!tool.error;
  const Icon = diff.mode === "write" ? FilePlus2 : FilePen;

  return (
    <div className="message-row message-row-tool fade-in">
      <details className="file-diff" open={!failed}>
        <summary className="file-diff-summary">
          <ChevronRight className="file-diff-caret" size={14} strokeWidth={2.5} />
          <Icon size={13} strokeWidth={2} className="file-diff-icon" />
          <span className="file-diff-path mono">{diff.path}</span>
          <span className="file-diff-stats">
            {diff.additions > 0 ? <span className="file-diff-stat-add">+{diff.additions}</span> : null}
            {diff.deletions > 0 ? <span className="file-diff-stat-del">−{diff.deletions}</span> : null}
          </span>
          <span className={`file-diff-status file-diff-status-${pending ? "running" : failed ? "error" : "completed"}`} />
        </summary>
        <div className="file-diff-body">
          {failed ? (
            <div className="file-diff-error">{tool.result || "The edit failed."}</div>
          ) : diff.lines.length === 0 ? (
            <div className="file-diff-empty">{diff.mode === "write" ? "Empty file" : "No changes"}</div>
          ) : (
            <pre className="file-diff-code">
              {diff.lines.map((line, index) => (
                <div key={index} className={`file-diff-line file-diff-line-${line.kind}`}>
                  <span className="file-diff-gutter">{line.kind === "add" ? "+" : "−"}</span>
                  <span className="file-diff-text">{line.text || " "}</span>
                </div>
              ))}
            </pre>
          )}
        </div>
      </details>
    </div>
  );
}
