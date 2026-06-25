import { useCallback, useEffect, useState } from "react";

import type { AgentRun, AuthSession } from "../lib/types";
import type { Screen } from "../lib/screen";
import { cancelAgentRun, fetchAgentRuns } from "../services/runApi";

/**
 * Tracks agent runs still executing on the server (they continue after a client disconnect) and
 * exposes cancel. While the Agent threads screen is open it polls every few seconds so the list and
 * disconnect/cancel badges stay live.
 */
export function useAgentRuns(params: {
  session: AuthSession | null;
  screen: Screen;
  onError: (message: string) => void;
}) {
  const { session, screen, onError } = params;
  const [agentRuns, setAgentRuns] = useState<AgentRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  const loadAgentRuns = useCallback(async () => {
    if (!session) return;
    setLoading(true);
    try {
      setAgentRuns(await fetchAgentRuns(session.token));
    } catch {
      // Keep the last known runs on a transient failure so the poll doesn't flicker the list to
      // empty when a single request fails.
    } finally {
      setLoading(false);
    }
  }, [session]);

  const cancelRun = useCallback(
    async (id: string) => {
      if (!session) return;
      setBusyId(id);
      try {
        await cancelAgentRun(session.token, id);
        setAgentRuns((current) => current.map((run) => (run.id === id ? { ...run, cancellationRequested: true } : run)));
        await loadAgentRuns();
      } catch (e) {
        onError(e instanceof Error ? e.message : "Could not cancel agent thread.");
      } finally {
        setBusyId(null);
      }
    },
    [session, loadAgentRuns, onError]
  );

  useEffect(() => {
    if (!session || screen !== "agent-threads") return;
    void loadAgentRuns();
    const id = window.setInterval(() => {
      void loadAgentRuns();
    }, 3000);
    return () => window.clearInterval(id);
  }, [session, screen, loadAgentRuns]);

  return { agentRuns, agentRunsLoading: loading, agentRunBusyId: busyId, loadAgentRuns, cancelRun };
}
