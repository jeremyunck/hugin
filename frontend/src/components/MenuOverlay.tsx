import {
  Bot,
  Bug,
  Github,
  History,
  LogOut,
  MessageCirclePlus,
  Network,
  Puzzle,
  Settings,
  Settings2,
  X
} from "lucide-react";

import { COLORS } from "../lib/theme";

export function MenuOverlay(props: {
  username: string;
  roles: string[];
  githubConnected: boolean;
  reportBusy?: boolean;
  onReportBug?: () => void;
  onClose: () => void;
  onAgent: () => void;
  onGitHubRepo: () => void;
  onChat: () => void;
  onHistory: () => void;
  onAgentThreads: () => void;
  onIntegrations: () => void;
  onSettings: () => void;
  onPreferences: () => void;
  onSignOut: () => void;
}) {
  const {
    username,
    githubConnected,
    reportBusy,
    onReportBug,
    onClose,
    onAgent,
    onGitHubRepo,
    onChat,
    onHistory,
    onAgentThreads,
    onIntegrations,
    onSettings,
    onPreferences,
    onSignOut
  } = props;
  const initials = username.slice(0, 2).toUpperCase();

  // Selecting any menu entry should also dismiss the dropdown so the chosen screen is visible.
  const choose = (action: () => void) => () => {
    action();
    onClose();
  };

  return (
    <div className="menu-overlay">
      <button type="button" className="menu-backdrop backdrop-fade" onClick={onClose} aria-label="Close menu" />
      <div className="menu-dropdown">
        <div className="menu-top-actions">
          {onReportBug ? (
            <button
              type="button"
              className="header-action-button"
              onClick={onReportBug}
              disabled={reportBusy}
              aria-label="Report bug"
            >
              <Bug size={14} strokeWidth={2} />
              <span>{reportBusy ? "Saving…" : "Report bug"}</span>
            </button>
          ) : (
            <span />
          )}
          <button type="button" className="menu-close-button" onClick={onClose} aria-label="Close menu">
            <X size={18} strokeWidth={2.2} />
          </button>
        </div>

        <div className="menu-card">
          <nav className="menu-nav">
            <button type="button" className="menu-item" onClick={choose(onChat)}>
              <MessageCirclePlus size={18} strokeWidth={2} color={COLORS.ink} />
              <span>New chat</span>
            </button>
            <button type="button" className="menu-item" onClick={choose(onAgent)}>
              <Bot size={18} strokeWidth={2} color={COLORS.ink} />
              <span>Agent</span>
            </button>
            {githubConnected ? (
              <button type="button" className="menu-item" onClick={choose(onGitHubRepo)}>
                <Github size={18} strokeWidth={2} color={COLORS.ink} />
                <span>Project</span>
              </button>
            ) : null}
            <button type="button" className="menu-item" onClick={choose(onHistory)}>
              <History size={18} strokeWidth={2} color={COLORS.ink} />
              <span>History</span>
            </button>
            <button type="button" className="menu-item" onClick={choose(onAgentThreads)}>
              <Network size={18} strokeWidth={2} color={COLORS.ink} />
              <span>Agent threads</span>
            </button>
          </nav>

          <div className="menu-divider" />

          <nav className="menu-nav">
            <button type="button" className="menu-item" onClick={choose(onIntegrations)}>
              <Puzzle size={18} strokeWidth={2} color={COLORS.ink} />
              <span>Integrations</span>
            </button>
            <button type="button" className="menu-item" onClick={choose(onSettings)}>
              <Settings2 size={18} strokeWidth={2} color={COLORS.ink} />
              <span>Model settings</span>
            </button>
            <button type="button" className="menu-item" onClick={choose(onPreferences)}>
              <Settings size={18} strokeWidth={2} color={COLORS.ink} />
              <span>Settings</span>
            </button>
          </nav>

          <div className="menu-divider" />

          <div className="menu-profile">
            <div className="profile-avatar">{initials}</div>
            <div className="profile-copy">
              <div className="profile-name">{username}</div>
            </div>
          </div>

          <nav className="menu-nav">
            <button type="button" className="menu-item menu-item-danger" onClick={choose(onSignOut)}>
              <LogOut size={18} strokeWidth={2} color={COLORS.danger} />
              <span>Sign out</span>
            </button>
          </nav>
        </div>
      </div>
    </div>
  );
}
