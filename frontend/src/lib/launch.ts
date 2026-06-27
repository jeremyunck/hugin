// Helpers for the one-shot launch parameters Bouw reads from the URL (e.g. ?screen=integrations
// after a GitHub App install redirect) and the lightweight "last active thread" restore hint. These
// are UI-convenience values only — the backend remains the source of truth for chat history.

const ACTIVE_THREAD_STORAGE_KEY = "bouw-active-thread-v1";

export function readLaunchScreen() {
  if (typeof window === "undefined") return null;
  const params = new URLSearchParams(window.location.search);
  return params.get("screen");
}

export function clearLaunchScreen() {
  if (typeof window === "undefined") return;
  const url = new URL(window.location.href);
  url.searchParams.delete("screen");
  url.searchParams.delete("github");
  url.searchParams.delete("installation_id");
  url.searchParams.delete("setup_action");
  window.history.replaceState({}, "", url.toString());
}

export function readActiveThreadRestore(): { threadId?: string; screen?: string } | null {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(ACTIVE_THREAD_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { threadId?: string; screen?: string };
    if (!parsed.threadId) return null;
    return parsed;
  } catch {
    return null;
  }
}
