import { useCallback, useEffect, useState } from "react";

import type { AuthSession, McpCatalogEntry, McpServer, McpTestResult } from "../lib/types";
import type { Screen } from "../lib/screen";
import {
  createMcpServer,
  deleteMcpServer,
  discoverMcpTools,
  fetchMcpCatalog,
  fetchMcpServers,
  setMcpToolEnabled,
  startMcpOAuth,
  testMcpServer,
  updateMcpServer,
  type McpCreatePayload,
  type McpUpdatePayload
} from "../services/mcpApi";

/**
 * Owns the MCP servers list and the create/update/delete/test/discover/toggle actions for the
 * Integrations screen. Loads when the Integrations screen becomes active. All calls are scoped to the
 * authenticated user by the backend, so this hook only ever sees the current user's servers.
 */
export function useMcpServers(params: {
  session: AuthSession | null;
  screen: Screen;
  onError: (message: string) => void;
  onChanged?: () => void;
}) {
  const { session, screen, onError, onChanged } = params;
  const [servers, setServers] = useState<McpServer[]>([]);
  const [catalog, setCatalog] = useState<McpCatalogEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [testResults, setTestResults] = useState<Record<string, McpTestResult>>({});

  const load = useCallback(async () => {
    if (!session) return;
    setLoading(true);
    try {
      setServers(await fetchMcpServers(session.token));
    } catch (e) {
      onError(e instanceof Error ? e.message : "Could not load MCP servers.");
    } finally {
      setLoading(false);
    }
  }, [session, onError]);

  useEffect(() => {
    if (!session || screen !== "integrations") return;
    void load();
    // Catalog is static; load it once when the screen opens (best-effort).
    fetchMcpCatalog(session.token).then(setCatalog).catch(() => setCatalog([]));
  }, [session, screen, load]);

  // Re-poll the server list a few times after an OAuth popup so a card flips to "connected"
  // once the callback stores tokens, without a manual refresh.
  const pollAfterOAuth = useCallback(async () => {
    if (!session) return;
    for (let attempt = 0; attempt < 20; attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 1500));
      try {
        const next = await fetchMcpServers(session.token);
        setServers(next);
        if (next.some((s) => s.oauthConnected)) {
          onChanged?.();
          return;
        }
      } catch {
        // keep polling
      }
    }
  }, [session, onChanged]);

  const connectOAuth = useCallback(
    async (id: string): Promise<void> => {
      if (!session) return;
      setBusyId(id);
      try {
        const url = await startMcpOAuth(session.token, id);
        window.open(url, "_blank", "noopener,width=600,height=720");
        void pollAfterOAuth();
      } catch (e) {
        onError(e instanceof Error ? e.message : "Could not start OAuth.");
      } finally {
        setBusyId(null);
      }
    },
    [session, onError, pollAfterOAuth]
  );

  const create = useCallback(
    async (payload: McpCreatePayload, discoverAfter: boolean): Promise<McpServer | null> => {
      if (!session) return null;
      setBusyId("new");
      try {
        const created = await createMcpServer(session.token, payload);
        if (discoverAfter) {
          try {
            await discoverMcpTools(session.token, created.id);
          } catch (e) {
            onError(e instanceof Error ? e.message : "Server created, but tool discovery failed.");
          }
        }
        await load();
        onChanged?.();
        return created;
      } catch (e) {
        onError(e instanceof Error ? e.message : "Could not create MCP server.");
        return null;
      } finally {
        setBusyId(null);
      }
    },
    [session, load, onError, onChanged]
  );

  const update = useCallback(
    async (id: string, payload: McpUpdatePayload): Promise<boolean> => {
      if (!session) return false;
      setBusyId(id);
      try {
        await updateMcpServer(session.token, id, payload);
        await load();
        onChanged?.();
        return true;
      } catch (e) {
        onError(e instanceof Error ? e.message : "Could not update MCP server.");
        return false;
      } finally {
        setBusyId(null);
      }
    },
    [session, load, onError, onChanged]
  );

  const remove = useCallback(
    async (id: string): Promise<void> => {
      if (!session) return;
      setBusyId(id);
      try {
        await deleteMcpServer(session.token, id);
        await load();
        onChanged?.();
      } catch (e) {
        onError(e instanceof Error ? e.message : "Could not remove MCP server.");
      } finally {
        setBusyId(null);
      }
    },
    [session, load, onError, onChanged]
  );

  const test = useCallback(
    async (id: string): Promise<void> => {
      if (!session) return;
      setBusyId(id);
      try {
        const result = await testMcpServer(session.token, id);
        setTestResults((current) => ({ ...current, [id]: result }));
      } catch (e) {
        setTestResults((current) => ({
          ...current,
          [id]: {
            success: false,
            message: e instanceof Error ? e.message : "Connection test failed.",
            serverName: null,
            serverVersion: null,
            protocolVersion: null
          }
        }));
      } finally {
        setBusyId(null);
      }
    },
    [session]
  );

  const discover = useCallback(
    async (id: string): Promise<void> => {
      if (!session) return;
      setBusyId(id);
      try {
        const result = await discoverMcpTools(session.token, id);
        if (!result.success) {
          onError(result.message);
        }
        await load();
        onChanged?.();
      } catch (e) {
        onError(e instanceof Error ? e.message : "Tool discovery failed.");
      } finally {
        setBusyId(null);
      }
    },
    [session, load, onError, onChanged]
  );

  const toggleTool = useCallback(
    async (serverId: string, toolId: string, enabled: boolean): Promise<void> => {
      if (!session) return;
      // Optimistic update so the toggle feels instant.
      setServers((current) =>
        current.map((server) =>
          server.id === serverId
            ? {
                ...server,
                tools: server.tools.map((tool) =>
                  tool.id === toolId ? { ...tool, enabled } : tool
                )
              }
            : server
        )
      );
      try {
        await setMcpToolEnabled(session.token, serverId, toolId, enabled);
        onChanged?.();
      } catch (e) {
        onError(e instanceof Error ? e.message : "Could not update tool.");
        await load();
      }
    },
    [session, load, onError, onChanged]
  );

  return {
    mcpServers: servers,
    mcpCatalog: catalog,
    mcpLoading: loading,
    mcpBusyId: busyId,
    mcpTestResults: testResults,
    loadMcpServers: load,
    createMcpServer: create,
    updateMcpServer: update,
    removeMcpServer: remove,
    testMcpServer: test,
    discoverMcpTools: discover,
    toggleMcpTool: toggleTool,
    connectMcpOAuth: connectOAuth
  };
}
