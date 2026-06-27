import { FolderGit2, Home, MessageCirclePlus, Puzzle, Search, Settings } from "lucide-react";

import type { ChatThread } from "../../lib/types";
import type { Screen } from "../../lib/screen";
import type { OpenRouterCredits } from "../../services/apiClient";

const LOGO = "/hugin-bird.jpg";

/** Formats a credit amount as a compact dollar figure (OpenRouter credits are denominated in USD). */
function formatCredits(value: number): string {
  return `$${value.toFixed(2)}`;
}

export function DesktopSidebar(props: {
  username: string;
  screen: Screen;
  threads: ChatThread[];
  activeThreadId?: string;
  githubConnected: boolean;
  onNewChat: () => void;
  onHome: () => void;
  onSearch: () => void;
  onProjects: () => void;
  onIntegrations: () => void;
  onSettings: () => void;
  onThread: (thread: ChatThread) => void;
  openRouterCredits: OpenRouterCredits | null;
}) {
  const initials = props.username.slice(0, 2).toUpperCase();
  const recentThreads = props.threads.slice(0, 12);
  const onChat = props.screen === "purechat" || props.screen === "chat";

  const credits = props.openRouterCredits;
  const hasBalance =
    credits?.configured === true && credits.remaining != null && credits.totalCredits != null;
  // Fraction of purchased credits already spent, so the bar fills as the balance is consumed.
  const usedFraction =
    hasBalance && credits!.totalCredits! > 0
      ? Math.min(1, Math.max(0, (credits!.totalUsage ?? 0) / credits!.totalCredits!))
      : 0;

  return (
    <aside className="desktop-sidebar">
      {/* Brand */}
      <div className="ds-brand">
        <img src={LOGO} alt="Hugin" className="brand-logo" />
        <span className="ds-brand-name">Hugin</span>
      </div>

      {/* New Chat button */}
      <div className="ds-top-actions">
        <button type="button" className="ds-new-chat-btn" onClick={props.onNewChat}>
          <MessageCirclePlus size={15} strokeWidth={2} />
          <span>New Chat</span>
        </button>
      </div>

      {/* Navigation */}
      <nav className="ds-nav">
        <button
          type="button"
          className={`ds-nav-item ${onChat ? "ds-nav-item-active" : ""}`}
          onClick={props.onHome}
        >
          <Home size={16} strokeWidth={2} />
          <span>Home</span>
        </button>
        <button
          type="button"
          className={`ds-nav-item ${props.screen === "history" ? "ds-nav-item-active" : ""}`}
          onClick={props.onSearch}
        >
          <Search size={16} strokeWidth={2} />
          <span>Search</span>
        </button>
        <button
          type="button"
          className={`ds-nav-item ${props.screen === "github-repo" ? "ds-nav-item-active" : ""}`}
          onClick={props.onProjects}
        >
          <FolderGit2 size={16} strokeWidth={2} />
          <span>Projects</span>
        </button>
        <button
          type="button"
          className={`ds-nav-item ${props.screen === "integrations" ? "ds-nav-item-active" : ""}`}
          onClick={props.onIntegrations}
        >
          <Puzzle size={16} strokeWidth={2} />
          <span>Integrations</span>
        </button>
        <button
          type="button"
          className={`ds-nav-item ${props.screen === "preferences" || props.screen === "settings" ? "ds-nav-item-active" : ""}`}
          onClick={props.onSettings}
        >
          <Settings size={16} strokeWidth={2} />
          <span>Settings</span>
        </button>
      </nav>

      {/* Recent Chats */}
      {recentThreads.length > 0 ? (
        <div className="ds-recents">
          <div className="ds-section-label">Recent Chats</div>
          <div className="ds-recent-list">
            {recentThreads.map((thread) => (
              <button
                key={thread.id}
                type="button"
                className={`ds-recent-item ${thread.id === props.activeThreadId ? "ds-recent-item-active" : ""}`}
                onClick={() => props.onThread(thread)}
                title={thread.title}
              >
                <span className="ds-recent-title">{thread.title || "New chat"}</span>
              </button>
            ))}
          </div>
        </div>
      ) : null}

      <div className="ds-spacer" />

      {/* OpenRouter credit card */}
      <div className="ds-credit-card">
        <div className="ds-credit-row">
          <span className="ds-credit-label">OpenRouter</span>
          {hasBalance ? (
            <span className="ds-credit-value">{formatCredits(credits!.remaining!)} left</span>
          ) : null}
        </div>
        {hasBalance ? (
          <div className="ds-credit-bar-wrap">
            <div className="ds-credit-bar">
              <div className="ds-credit-bar-fill" style={{ width: `${usedFraction * 100}%` }} />
            </div>
          </div>
        ) : (
          <p className="ds-credit-empty">
            {credits == null
              ? "Loading…"
              : credits.configured === false
                ? "No API key connected."
                : credits.error
                  ? "Couldn’t load balance."
                  : "Balance unavailable."}
          </p>
        )}
        <a
          href="https://openrouter.ai/settings/credits"
          target="_blank"
          rel="noopener noreferrer"
          className="ds-credit-manage-btn"
        >
          Manage Credits
        </a>
      </div>

      {/* Profile footer */}
      <div className="ds-profile">
        <div className="profile-avatar">{initials}</div>
        <div className="profile-copy">
          <div className="profile-name">{props.username}</div>
        </div>
      </div>
    </aside>
  );
}
