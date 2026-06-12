import { Clock3, ArrowLeft } from "lucide-react";
import type { ChatThread, Route } from "../lib/types";
import { Button, Card } from "../components/Ui";
import { formatRelative } from "../services/guildService";

export function HistoryScreen({
  threads,
  onOpenThread,
  onNavigate
}: {
  threads: ChatThread[];
  onOpenThread: (threadId: string) => void;
  onNavigate: (route: Route) => void;
}) {
  const groups = groupThreads(threads);
  return (
    <section className="screen-frame">
      <div className="screen-head">
        <div className="screen-title-block">
          <Button variant="ghost" className="back-button mobile-only-inline" onClick={() => onNavigate({ screen: "new-chat" })}>
            <ArrowLeft size={16} />
            Back
          </Button>
          <div className="eyebrow">Guild</div>
          <h1>History</h1>
          <p>Open any saved conversation. Selecting a row opens the full chat.</p>
        </div>
      </div>

      <Card className="history-card">
        {groups.length ? groups.map((group) => (
          <div className="history-group" key={group.label}>
            <div className="history-group-label">{group.label}</div>
            <div className="history-list">
              {group.items.map((thread) => (
                <button className="history-row" key={thread.id} onClick={() => onOpenThread(thread.id)}>
                  <div className="history-icon">
                    <Clock3 size={16} />
                  </div>
                  <div className="history-copy">
                    <div className="history-title">{thread.title}</div>
                    <div className="history-preview">{thread.messages[thread.messages.length - 1]?.content.slice(0, 80) || "No messages yet"}</div>
                  </div>
                  <div className="history-meta">{formatRelative(thread.updatedAt)}</div>
                </button>
              ))}
            </div>
          </div>
        )) : <p className="history-preview">Your saved conversations will appear here after you start chatting.</p>}
      </Card>
    </section>
  );
}

function groupThreads(threads: ChatThread[]) {
  const sorted = [...threads].sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime());
  const today: ChatThread[] = [];
  const earlier: ChatThread[] = [];
  const cutoff = Date.now() - 24 * 60 * 60_000;

  for (const thread of sorted) {
    if (new Date(thread.updatedAt).getTime() >= cutoff) today.push(thread);
    else earlier.push(thread);
  }

  return [
    { label: "Today", items: today },
    { label: "Earlier", items: earlier }
  ].filter((group) => group.items.length > 0);
}
