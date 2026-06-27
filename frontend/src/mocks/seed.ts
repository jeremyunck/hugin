import type { Screen } from "../lib/screen";
import { readMockScreen } from "./env";
import { mockSession } from "./fixtures/auth";
import {
  MOCK_AGENT_THREAD_ID,
  MOCK_GITHUB_THREAD_ID,
  MOCK_PURECHAT_THREAD_ID,
  mockThreads
} from "./fixtures/threads";

/**
 * Seeds browser storage so the app boots straight into the signed-in experience with realistic data,
 * driven entirely through the code paths the real app already uses:
 *
 *   - The auth session is written where {@link loadAuthSession} reads it, so `useAuthBootstrap`
 *     restores a logged-in user (no Spring Boot, no login, no OpenRouter/GitHub setup required).
 *   - The thread index + active-thread hint are written where the chat session store and
 *     {@link restorePreferredThread} read them, so the History list and the active chat populate
 *     without any mock-aware code in the store or the App shell.
 *
 * Everything else (transcripts, models, integrations, GitHub, workspace files) flows through the
 * normal service calls, which route to the mock fixtures via {@link mockApiFetch}. Because this
 * module is dynamically imported behind a `VITE_BOUW_MOCK_MODE` guard, none of it — or the fixture
 * data — ships in a normal build.
 */

// Stable storage-key contract shared with the production loaders. Kept in sync with:
//   apiClient.ts          -> AUTH_STORAGE_KEY
//   chatSessionStore.ts   -> THREAD_INDEX_KEY
//   chatSessionStore.ts / launch.ts -> the active-thread hint
const AUTH_STORAGE_KEY = "bouw-auth-session-v1";
const THREAD_INDEX_KEY = "bouw-ui-thread-index-v1";
const ACTIVE_THREAD_KEY = "bouw-active-thread-v1";

/** The thread that should be active for a given screen so chat/project views have a transcript. */
function activeThreadIdForScreen(screen: Screen): string {
  if (screen === "chat") return MOCK_GITHUB_THREAD_ID; // project chat with a workspace panel
  if (screen === "agent-threads") return MOCK_AGENT_THREAD_ID;
  return MOCK_PURECHAT_THREAD_ID; // plain chat + every non-chat screen
}

export function seedMockStorage(): void {
  if (typeof window === "undefined") return;

  const screen = readMockScreen() ?? "purechat";

  // The login screen is the one place we want an unauthenticated shell: clear any seeded session so
  // the sidebar-less login form renders cleanly.
  if (screen === "login") {
    window.localStorage.removeItem(AUTH_STORAGE_KEY);
    window.sessionStorage.removeItem(AUTH_STORAGE_KEY);
    return;
  }

  window.localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(mockSession));
  window.sessionStorage.removeItem(AUTH_STORAGE_KEY);

  // Lightweight metadata index — exactly the shape the store persists; transcripts are hydrated from
  // the (mocked) event log on demand, just like production.
  const index = mockThreads.map((thread) => ({
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
  }));
  window.localStorage.setItem(THREAD_INDEX_KEY, JSON.stringify(index));

  // The active-thread hint carries the screen too (read by restorePreferredThread), so `?mockScreen`
  // deterministically opens the right screen with the right thread.
  window.localStorage.setItem(
    ACTIVE_THREAD_KEY,
    JSON.stringify({ threadId: activeThreadIdForScreen(screen), screen })
  );
}
