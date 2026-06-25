import { ArrowLeft } from "lucide-react";

import type { AgentRun, ChatThread } from "../lib/types";
import { formatTimestamp } from "../services/apiClient";

/**
 * Lists agent runs still executing on the server. Runs continue server-side after a client
 * disconnect; this screen surfaces them and lets the user cancel one.
 */
export function AgentThreadsScreen(props: {
  runs: AgentRun[];
  threads: ChatThread[];
  busyRunId: string | null;
  loading: boolean;
  onBack: () => void;
  onCancel: (id: string) => void;
}) {
  const { runs, threads, busyRunId, loading, onBack, onCancel } = props;

  const labelForRun = (run: AgentRun) => {
    const match = run.sessionId ? threads.find((thread) => thread.id === run.sessionId) : null;
    if (match) return match.title;
    if (run.prompt) return run.prompt;
    return run.sessionId || "Active run";
  };

  return (
    <>
      <div className="back-row">
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">Agent threads</h1>
        <p className="integration-subtitle">
          Running agent requests continue on the server after a client disconnect. Cancel them here when needed.
        </p>
      </div>

      <div className="integrations-list">
        <div className="history-group-label">ACTIVE RUNS</div>
        {loading && runs.length === 0 ? (
          <p className="history-empty">Loading active runs…</p>
        ) : runs.length === 0 ? (
          <p className="history-empty">No active agent threads.</p>
        ) : (
          runs.map((run) => (
            <div key={run.id} className="integration-card">
              <div className="integration-copy">
                <div className="integration-name-row">
                  <span className="integration-name">{labelForRun(run)}</span>
                  {run.disconnected ? <span className="integration-badge">DISCONNECTED</span> : null}
                  {run.cancellationRequested ? <span className="integration-badge">CANCELLING</span> : null}
                </div>
                <div className="integration-meta">{run.model || "Default model"}</div>
                <div className="model-description">
                  Started {formatTimestamp(run.startedAt)}
                  {run.sessionId ? ` • ${run.sessionId}` : ""}
                </div>
              </div>
              <button
                type="button"
                className="secondary-button"
                disabled={run.cancellationRequested || busyRunId === run.id}
                onClick={() => onCancel(run.id)}
              >
                {busyRunId === run.id || run.cancellationRequested ? "Cancelling…" : "Cancel"}
              </button>
            </div>
          ))
        )}
      </div>
    </>
  );
}
