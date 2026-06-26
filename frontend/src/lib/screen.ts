import type { ChatThread } from "./types";

export type Screen =
  | "login"
  | "chat"
  | "purechat"
  | "history"
  | "integrations"
  | "settings"
  | "preferences"
  | "github-repo"
  | "agent-threads"
  | "user-details"
  | "password-reset";

export function screenForThread(thread: ChatThread): Screen {
  // Agent and Project threads carry a workspace file tree, so they use the "chat" screen; a plain
  // chat has no workspace and uses "purechat".
  return thread.kind === "chat" ? "purechat" : "chat";
}

export function mostRecentThread(threads: ChatThread[]): ChatThread | null {
  if (!threads.length) return null;
  return threads.reduce((latest, candidate) =>
    Date.parse(candidate.updatedAt) > Date.parse(latest.updatedAt) ? candidate : latest
  );
}
