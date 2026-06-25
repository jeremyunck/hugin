import { useCallback, useEffect, useRef, useState } from "react";

import type { AuthSession, ChatThread, FileNode } from "../lib/types";
import type { Screen } from "../lib/screen";
import { fetchAgentWorkspaceFiles, fetchSandbox, fetchSandboxFiles } from "../services/runApi";

/**
 * Owns the workspace file-tree state that backs Agent mode (the server home directory, ~/) and
 * Project mode (a per-session sandbox). It loads the right tree when the active chat changes and
 * refreshes it whenever a run finishes so files the agent created/edited are reflected.
 */
export function useWorkspaceState(params: {
  session: AuthSession | null;
  thread: ChatThread | null;
  screen: Screen;
  busy: boolean;
}) {
  const { session, thread, screen, busy } = params;
  const [files, setFiles] = useState<FileNode[]>([]);
  const [sandboxStatus, setSandboxStatus] = useState<string | undefined>(undefined);

  // Mirror the active sandbox id / kind so post-run refreshes can read them without depending on the
  // thread object identity (which would otherwise re-run the file-loading effect every render).
  const activeSandboxRef = useRef<string | undefined>(thread?.sandboxId);
  activeSandboxRef.current = thread?.sandboxId;
  const activeKindRef = useRef(thread?.kind);
  activeKindRef.current = thread?.kind;

  const refreshFiles = useCallback(
    async (sandboxId?: string) => {
      const id = sandboxId ?? activeSandboxRef.current;
      if (!id || !session) return;
      try {
        setFiles(await fetchSandboxFiles(session.token, id));
      } catch {
        setFiles([]);
      }
      // Surface the isolated container's health alongside the file tree (Sandbox Ready/Starting/Failed).
      try {
        setSandboxStatus((await fetchSandbox(session.token, id)).status);
      } catch {
        setSandboxStatus(undefined);
      }
    },
    [session]
  );

  // Loads the server home directory (~/) file tree that backs Agent-mode chats.
  const refreshAgentFiles = useCallback(async () => {
    if (!session) return;
    try {
      setFiles(await fetchAgentWorkspaceFiles(session.token));
    } catch {
      setFiles([]);
    }
  }, [session]);

  useEffect(() => {
    if (!session || screen !== "chat" || !thread) {
      setFiles([]);
      setSandboxStatus(undefined);
      return;
    }
    setFiles([]);
    setSandboxStatus(undefined);
    if (thread.kind === "agent") {
      void refreshAgentFiles();
    } else if (thread.sandboxId) {
      void refreshFiles(thread.sandboxId);
    }
  }, [session, screen, thread?.id, thread?.kind, thread?.sandboxId, refreshFiles, refreshAgentFiles]);

  // Refresh the workspace file tree whenever a run finishes (busy true -> false), so files the agent
  // created/edited during the run are reflected.
  const prevBusyRef = useRef(busy);
  useEffect(() => {
    const justFinished = prevBusyRef.current && !busy;
    prevBusyRef.current = busy;
    if (justFinished && screen === "chat") {
      if (activeKindRef.current === "agent") {
        void refreshAgentFiles();
      } else if (activeSandboxRef.current) {
        void refreshFiles(activeSandboxRef.current);
      }
    }
  }, [busy, screen, refreshFiles, refreshAgentFiles]);

  return { files, setFiles, sandboxStatus, refreshFiles, refreshAgentFiles };
}
