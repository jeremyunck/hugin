import { ArrowLeft, Bug, Menu } from "lucide-react";

const LOGO = "/bouw-bird.jpg";

/**
 * Branded top bar. In primary mode (no backAction) shows the logo and hamburger menu.
 * In secondary mode (backAction present) shows a back arrow, the screen title, and
 * optionally a hamburger menu — creating a consistent header across all screens.
 */
export function AppHeader({
  onMenu,
  reportAction,
  backAction,
  title,
}: {
  onMenu?: () => void;
  reportAction?: {
    disabled?: boolean;
    busy?: boolean;
    onClick: () => void;
  };
  backAction?: { onClick: () => void };
  title?: string;
}) {
  if (backAction) {
    return (
      <div className="app-header app-header-secondary">
        <button type="button" className="icon-button" onClick={backAction.onClick} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
        <span className="header-title">{title ?? ""}</span>
        {onMenu ? (
          <button type="button" className="icon-button" onClick={onMenu} aria-label="Open menu">
            <Menu size={22} strokeWidth={2} />
          </button>
        ) : (
          <span style={{ width: 36 }} />
        )}
      </div>
    );
  }

  return (
    <div className="app-header">
      <div className="brand">
        <img src={LOGO} alt="Bouw" className="brand-logo" />
      </div>
      <div className="header-actions">
        {reportAction ? (
          <button
            type="button"
            className="header-action-button"
            onClick={reportAction.onClick}
            disabled={reportAction.disabled || reportAction.busy}
            aria-label="Report bug"
          >
            <Bug size={14} strokeWidth={2} />
            <span>{reportAction.busy ? "Saving…" : "Report bug"}</span>
          </button>
        ) : null}
        {onMenu ? (
          <button type="button" className="icon-button" onClick={onMenu} aria-label="Open menu">
            <Menu size={22} strokeWidth={2} />
          </button>
        ) : null}
      </div>
    </div>
  );
}
