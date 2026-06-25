import { useCallback, useState } from "react";

import type { AuthSession, ChatAttachment, ChatThread } from "../lib/types";
import type { Screen } from "../lib/screen";
import { createThread, deleteThreadHistory } from "../services/threadApi";
import { deleteSandbox } from "../services/runApi";
import type { useChatSessionStore } from "../stores/chatSessionStore";

type Store = ReturnType<typeof useChatSessionStore>;

/**
 * Owns selecting / starting / deleting the active thread and the small bits of list UI state
 * (history search query, in-flight delete). Backend remains the source of truth for the transcript;
 * switching a thread re-hydrates it from the server event log via the chat session store.
 */
export function useThreadSelection(params: {
  session: AuthSession | null;
  store: Store;
  setScreen: (screen: Screen) => void;
  setMenuOpen: (open: boolean) => void;
  setFiles: (files: never[]) => void;
  setWsOpen: (open: boolean) => void;
  setDraftAttachment: (attachment: ChatAttachment | null) => void;
  setBugReportNotice: (notice: string | null) => void;
  setError: (message: string | null) => void;
  refreshFiles: (sandboxId?: string) => Promise<void> | void;
  refreshAgentFiles: () => Promise<void> | void;
}) {
  const {
    session,
    store,
    setScreen,
    setMenuOpen,
    setFiles,
    setWsOpen,
    setDraftAttachment,
    setBugReportNotice,
    setError,
    refreshFiles,
    refreshAgentFiles
  } = params;

  const [historyQuery, setHistoryQuery] = useState("");
  const [deletingThreadId, setDeletingThreadId] = useState<string | null>(null);

  const startChat = useCallback(() => {
    store.switchThread(createThread("chat"));
    setFiles([]);
    setWsOpen(true);
    setDraftAttachment(null);
    setBugReportNotice(null);
    setError(null);
    setHistoryQuery("");
    setScreen("purechat");
    setMenuOpen(false);
  }, [store, setFiles, setWsOpen, setDraftAttachment, setBugReportNotice, setError, setScreen, setMenuOpen]);

  const startAgent = useCallback(() => {
    if (!session) return;
    setMenuOpen(false);
    setHistoryQuery("");
    setBugReportNotice(null);
    setError(null);
    setFiles([]);
    setWsOpen(true);
    setDraftAttachment(null);
    // Agent mode runs against the server home directory (~/); no sandbox is provisioned. The thread
    // is created locally and the home file tree is loaded for the workspace panel.
    store.switchThread(createThread("agent"));
    setScreen("chat");
    void refreshAgentFiles();
  }, [session, store, refreshAgentFiles, setFiles, setWsOpen, setDraftAttachment, setBugReportNotice, setError, setMenuOpen, setScreen]);

  const openHistory = useCallback(
    (item: ChatThread) => {
      store.switchThread(item);
      setDraftAttachment(null);
      setBugReportNotice(null);
      setError(null);
      setMenuOpen(false);
      if (item.kind === "agent") {
        setScreen("chat");
        setFiles([]);
        setWsOpen(true);
        void refreshAgentFiles();
      } else if (item.kind === "github") {
        setScreen("chat");
        setFiles([]);
        setWsOpen(false);
        if (item.sandboxId) void refreshFiles(item.sandboxId);
      } else {
        setScreen("purechat");
      }
    },
    [store, refreshFiles, refreshAgentFiles, setFiles, setWsOpen, setDraftAttachment, setBugReportNotice, setError, setMenuOpen, setScreen]
  );

  const deleteThread = useCallback(
    async (item: ChatThread) => {
      if (deletingThreadId) return;
      const confirmed = typeof window === "undefined"
        ? true
        : window.confirm(`Delete “${item.title}”? This also removes any sandbox created for it.`);
      if (!confirmed) return;

      setDeletingThreadId(item.id);
      setError(null);
      try {
        // Tear down the sandbox first so a backend failure leaves the chat in the list to retry.
        if (item.sandboxId && session) {
          await deleteSandbox(session.token, item.sandboxId);
        }
        if (session) {
          await deleteThreadHistory(session.token, item.id);
        }

        const wasActive = store.activeThreadId === item.id;
        store.removeThread(item.id);
        if (wasActive) {
          store.switchThread(createThread("chat"));
          setFiles([]);
          setScreen("purechat");
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : "Could not delete this conversation.");
      } finally {
        setDeletingThreadId(null);
      }
    },
    [deletingThreadId, session, store, setFiles, setError, setScreen]
  );

  return {
    historyQuery,
    setHistoryQuery,
    deletingThreadId,
    startChat,
    startAgent,
    openHistory,
    deleteThread
  };
}
