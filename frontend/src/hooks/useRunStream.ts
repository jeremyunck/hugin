import { useMemo } from "react";

import type { ApprovalDecision } from "../lib/types";
import type { useChatSessionStore } from "../stores/chatSessionStore";

type Store = ReturnType<typeof useChatSessionStore>;

/**
 * Run-stream view over the chat session store for the active thread.
 *
 * The append-only SSE engine — persist-before-emit ordering, per-run sequence tracking, reconnect
 * with `afterSeq`, and de-dup by `runId + sequenceNumber` — lives in
 * {@link ../stores/chatSessionStore} (and its reducer) so the same projection is produced whether
 * events arrive from `GET /events` or live SSE. This hook is the thin, focused accessor the chat
 * screen uses: it exposes the active run's busy flag plus the run controls (stop / approval), keyed
 * to the active thread, without the caller threading the thread id through each call site.
 */
export function useRunStream(store: Store) {
  const threadId = store.activeThreadId;

  return useMemo(
    () => ({
      running: store.activeBusy,
      stop: () => {
        if (threadId) void store.cancelRun(threadId);
      },
      resolveApproval: (approvalId: string, decision: ApprovalDecision) => {
        if (threadId) void store.resolveApproval(threadId, approvalId, decision);
      }
    }),
    [store, threadId]
  );
}
