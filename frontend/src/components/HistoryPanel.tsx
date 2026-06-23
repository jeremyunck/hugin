import {
  Bot,
  ChevronRight,
  GitBranch,
  MessageSquare,
  Plus,
  Search,
  SlidersHorizontal,
  Trash2
} from "lucide-react";

import type { ChatThread } from "../lib/types";
import { COLORS } from "../lib/theme";
import { formatTimestamp } from "../services/guildService";
import { AppHeader } from "./AppHeader";

function messageCount(thread: ChatThread) {
  return thread.entries.filter((entry) => entry.type !== "tool").length;
}

/** History list of past conversations, grouped by recency, with search and delete. */
export function HistoryPanel(props: {
  threads: ChatThread[];
  onMenu: () => void;
  onOpen: (thread: ChatThread) => void;
  onDelete: (thread: ChatThread) => void;
  onNew: () => void;
  deletingId: string | null;
  query: string;
  onQuery: (value: string) => void;
}) {
  const { threads, onMenu, onOpen, onNew, onDelete, deletingId, query, onQuery } = props;
  const lower = query.trim().toLowerCase();
  const match = (thread: ChatThread) => !lower || thread.title.toLowerCase().includes(lower);
  const now = Date.now();
  const isToday = (iso: string) => now - new Date(iso).getTime() < 24 * 60 * 60 * 1000;

  const matched = threads.filter(match);
  const groups: Array<[string, ChatThread[]]> = [
    ["TODAY", matched.filter((thread) => isToday(thread.updatedAt))],
    ["EARLIER", matched.filter((thread) => !isToday(thread.updatedAt))]
  ];
  const anyResults = matched.length > 0;

  return (
    <>
      <AppHeader onMenu={onMenu} />
      <h1 className="screen-title">History</h1>

      <div className="screen-pad">
        <div className="search-bar">
          <Search size={17} strokeWidth={2} color={COLORS.faint} />
          <input
            value={query}
            onChange={(event) => onQuery(event.target.value)}
            placeholder="Search history…"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
          />
          <SlidersHorizontal size={16} strokeWidth={2} color={COLORS.faint} />
        </div>
      </div>

      <div className="history-list">
        {groups.map(([label, items], groupIndex) => {
          if (!items.length) return null;
          return (
            <div key={label} className={groupIndex > 0 ? "history-group history-group-spaced" : "history-group"}>
              <div className="history-group-label">{label}</div>
              <div className="history-cards">
                {items.map((thread) => (
                  <div key={thread.id} className="history-card-row">
                    <button type="button" className="history-card" onClick={() => onOpen(thread)}>
                      <div className="history-card-icon">
                        {thread.kind === "agent" ? (
                          <Bot size={17} strokeWidth={2} color={COLORS.ink} />
                        ) : thread.kind === "github" ? (
                          <GitBranch size={17} strokeWidth={2} color={COLORS.ink} />
                        ) : (
                          <MessageSquare size={17} strokeWidth={2} color={COLORS.ink} />
                        )}
                      </div>
                      <div className="history-card-copy">
                        <div className="history-card-title">{thread.title}</div>
                        <div className="history-card-meta">
                          {formatTimestamp(thread.updatedAt)} · {messageCount(thread)} messages
                        </div>
                      </div>
                      <ChevronRight size={18} color={COLORS.faint} />
                    </button>
                    <button
                      type="button"
                      className="history-card-delete"
                      aria-label={`Delete ${thread.title}`}
                      title="Delete conversation"
                      disabled={deletingId === thread.id}
                      onClick={() => onDelete(thread)}
                    >
                      <Trash2 size={17} strokeWidth={2} color={COLORS.danger} />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
        {!anyResults ? (
          <p className="history-empty">
            {threads.length ? `No sessions match “${query.trim()}”.` : "No conversations yet."}
          </p>
        ) : null}
      </div>

      <div className="screen-pad history-footer">
        <button type="button" className="primary-button" onClick={onNew}>
          <Plus size={18} strokeWidth={2.4} /> New chat
        </button>
      </div>
    </>
  );
}
