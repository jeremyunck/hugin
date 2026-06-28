import { useState } from "react";
import {
  Activity,
  CheckCircle,
  ChevronRight,
  Plus,
  Search,
  ShieldCheck,
} from "lucide-react";

import type { AuthSession, Integration } from "../lib/types";
import type { Screen } from "../lib/screen";
import { AppHeader } from "./AppHeader";
import { McpServersSection } from "./McpServersSection";

/* ------------------------------------------------------------------ */
/*  Brand logo SVG components                                          */
/* ------------------------------------------------------------------ */

function GoogleLogo({ size = 26 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg" aria-label="Google">
      <path d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z" fill="#4285F4"/>
      <path d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z" fill="#34A853"/>
      <path d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l3.66-2.84z" fill="#FBBC05"/>
      <path d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z" fill="#EA4335"/>
    </svg>
  );
}

function GitHubLogo({ size = 26 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 98 96" xmlns="http://www.w3.org/2000/svg" aria-label="GitHub">
      <path
        fillRule="evenodd"
        clipRule="evenodd"
        d="M48.854 0C21.839 0 0 22 0 49.217c0 21.756 13.993 40.172 33.405 46.69 2.427.49 3.316-1.059 3.316-2.362 0-1.141-.08-5.052-.08-9.127-13.59 2.934-16.42-5.867-16.42-5.867-2.184-5.704-5.42-7.17-5.42-7.17-4.448-3.015.324-3.015.324-3.015 4.934.326 7.523 5.052 7.523 5.052 4.367 7.496 11.404 5.378 14.235 4.074.404-3.178 1.699-5.378 3.074-6.6-10.839-1.141-22.243-5.378-22.243-24.283 0-5.378 1.94-9.778 5.014-13.2-.485-1.222-2.184-6.275.486-13.038 0 0 4.125-1.304 13.426 5.052a46.97 46.97 0 0 1 12.214-1.63c4.125 0 8.33.571 12.213 1.63 9.302-6.356 13.427-5.052 13.427-5.052 2.67 6.763.97 11.816.485 13.038 3.155 3.422 5.015 7.822 5.015 13.2 0 18.905-11.404 23.06-22.324 24.283 1.78 1.548 3.316 4.481 3.316 9.126 0 6.6-.08 11.897-.08 13.526 0 1.304.89 2.853 3.316 2.364 19.412-6.52 33.405-24.935 33.405-46.691C97.707 22 75.788 0 48.854 0z"
        fill="currentColor"
      />
    </svg>
  );
}

/* ------------------------------------------------------------------ */
/*  Integration icon map                                               */
/* ------------------------------------------------------------------ */

type IntegrationIconProps = { size?: number };

const ICON_COMPONENT_MAP: Record<string, React.ComponentType<IntegrationIconProps>> = {
  google: GoogleLogo,
  github: GitHubLogo,
};

const LABEL_COLOR_MAP: Record<string, string> = {
  google: "#5F6368",
  github: "#1C1F23",
  web_search: "#8B9099",
};

const BG_COLOR_MAP: Record<string, string> = {
  google: "#fff",
  github: "#F0F0F1",
  web_search: "#F4F4F6",
};

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

function permissionLabel(integration: Integration): string {
  switch (integration.authMode) {
    case "oauth":
      return "All permissions granted";
    case "github-app":
      return "All permissions granted";
    case "api-key":
      return "No permissions required";
    case "none":
      return "Not configured";
    default:
      return integration.connected ? "All permissions granted" : "No permissions required";
  }
}

function toolCountLabel(integration: Integration): string {
  const count = integration.tools.length;
  return `${count} tool${count === 1 ? "" : "s"}`;
}

function isUserConnectable(integration: Integration): boolean {
  return integration.id === "google" || integration.id === "github";
}

/* ------------------------------------------------------------------ */
/*  Component                                                          */
/* ------------------------------------------------------------------ */

type Tab = "mine" | "browse";

export function IntegrationPanel(props: {
  integrations: Integration[];
  loading: boolean;
  error: string | null;
  busyId: string | null;
  session: AuthSession | null;
  screen: Screen;
  onBack: () => void;
  onToggle: (integration: Integration) => void;
  onReconnect: (integration: Integration) => void;
  onError: (message: string) => void;
  onMcpChanged: () => void;
}) {
  const { integrations, loading, error, busyId, session, screen, onBack, onToggle, onReconnect } = props;
  const [tab, setTab] = useState<Tab>("mine");

  // MCP has its own dedicated section below; keep it out of the generic connect/disconnect card lists.
  const cards = integrations.filter((i) => i.id !== "mcp");
  const connected = cards.filter((i) => i.connected);
  const available = cards.filter((i) => !i.connected);
  const allHealthy = connected.length > 0;

  return (
    <>
      <AppHeader backAction={{ onClick: onBack }} title="Integrations" />

      <div className="screen-content">
        <div className="screen-pad">
          <p className="integration-subtitle">
            Bouw can use these tools to find information, take action, and get things done for you.
          </p>
        </div>

        {/* ── Tabs ───────────────────────────────── */}
        <div className="screen-pad">
          <div className="integration-tabs" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={tab === "mine"}
              className={`integration-tab ${tab === "mine" ? "integration-tab-active" : ""}`}
              onClick={() => setTab("mine")}
            >
              My tools ({connected.length})
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={tab === "browse"}
              className={`integration-tab ${tab === "browse" ? "integration-tab-active" : ""}`}
              onClick={() => setTab("browse")}
            >
              Browse tools
            </button>
          </div>
        </div>

        {/* ── Loading / Error ────────────────────── */}
        {loading ? (
          <div className="screen-pad">
            <p className="integration-subtitle">Refreshing integration status…</p>
          </div>
        ) : null}
        {!loading && error ? (
          <div className="screen-pad">
            <p className="login-error">{error}</p>
          </div>
        ) : null}

        {/* ── My tools ───────────────────────────── */}
        {tab === "mine" ? (
          <div className="integrations-section">
            <div className="integrations-section-head">
              <div>
                <div className="integrations-section-label">Connected tools</div>
                <div className="integrations-section-sub">Tools Bouw can use right now</div>
              </div>
              {allHealthy ? (
                <span className="integrations-health">
                  <span className="integrations-health-dot" />
                  All healthy
                </span>
              ) : null}
            </div>

            {connected.length === 0 && !loading ? (
              <p className="history-empty">No connected tools yet.</p>
            ) : (
              <div className="integrations-cards">
                {connected.map((integration) => (
                  <ConnectedCard
                    key={integration.id}
                    integration={integration}
                    busyId={busyId}
                    onReconnect={onReconnect}
                    onToggle={onToggle}
                  />
                ))}
              </div>
            )}
          </div>
        ) : null}

        {/* ── Browse tools ───────────────────────── */}
        {tab === "browse" ? (
          <div className="integrations-section">
            <div className="integrations-section-head">
              <div>
                <div className="integrations-section-label">Available tools</div>
                <div className="integrations-section-sub">
                  Connect more tools to expand what Bouw can do
                </div>
              </div>
            </div>

            {available.length === 0 && !loading ? (
              <div className="integrations-empty">
                <CheckCircle size={24} strokeWidth={1.5} color="#1b8a4b" />
                <p className="integrations-empty-text">
                  Every available tool is already connected.
                </p>
              </div>
            ) : (
              <div className="integrations-cards">
                {available.map((integration) => (
                  <AvailableCard
                    key={integration.id}
                    integration={integration}
                    busyId={busyId}
                    onToggle={onToggle}
                  />
                ))}
              </div>
            )}
          </div>
        ) : null}

        {/* ── MCP servers (user-connected) ───────── */}
        <McpServersSection
          session={session}
          screen={screen}
          onError={props.onError}
          onChanged={props.onMcpChanged}
        />
      </div>
    </>
  );
}

/* ------------------------------------------------------------------ */
/*  Connected integration card                                         */
/* ------------------------------------------------------------------ */

function IntegrationIcon({ id, size = 26 }: { id: string; size?: number }) {
  const Component = ICON_COMPONENT_MAP[id];
  if (Component) return <Component size={size} />;
  return <Search size={size} strokeWidth={1.7} />;
}

function ConnectedCard({
  integration,
  busyId,
  onReconnect,
  onToggle,
}: {
  integration: Integration;
  busyId: string | null;
  onReconnect: (integration: Integration) => void;
  onToggle: (integration: Integration) => void;
}) {
  const iconColor = LABEL_COLOR_MAP[integration.id] ?? "#1C1F23";
  const iconBg = BG_COLOR_MAP[integration.id] ?? "#F4F4F6";
  const busy = busyId === integration.id;
  const connectable = isUserConnectable(integration);

  return (
    <div className="integration-card">
      <div
        className="integration-card-icon"
        style={{ background: iconBg, color: iconColor }}
      >
        <IntegrationIcon id={integration.id} />
      </div>

      <div className="integration-card-body">
        <div className="integration-card-title-row">
          <span className="integration-card-name">{integration.name}</span>
          <span className="integration-card-badge">
            <CheckCircle size={13} strokeWidth={2.5} />
            Connected
          </span>
          <ChevronRight size={18} strokeWidth={2} color="#c4c7cd" className="integration-card-chevron" />
        </div>

        {integration.description ? (
          <p className="integration-card-desc">{integration.description}</p>
        ) : null}

        <div className="integration-card-meta">
          <span className="integration-meta-item">
            <Activity size={13} strokeWidth={2} />
            {toolCountLabel(integration)}
          </span>
          <span className="integration-meta-item">
            <ShieldCheck size={13} strokeWidth={2} />
            {permissionLabel(integration)}
          </span>
          <span className="integration-meta-item integration-meta-health">
            <span className="integrations-health-dot" />
            Healthy
          </span>
        </div>

        {connectable ? (
          <div className="integration-card-actions">
            {integration.reconnectable ? (
              <button
                type="button"
                className="integration-link-action"
                disabled={busy}
                onClick={() => onReconnect(integration)}
              >
                {busy ? "Working…" : integration.id === "github" ? "Add account / org" : "Reconnect"}
              </button>
            ) : null}
            <button
              type="button"
              className="integration-link-action integration-link-danger"
              disabled={busy}
              onClick={() => onToggle(integration)}
            >
              Disconnect
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Available integration card                                         */
/* ------------------------------------------------------------------ */

function AvailableCard({
  integration,
  busyId,
  onToggle,
}: {
  integration: Integration;
  busyId: string | null;
  onToggle: (integration: Integration) => void;
}) {
  const iconColor = LABEL_COLOR_MAP[integration.id] ?? "#1C1F23";
  const iconBg = BG_COLOR_MAP[integration.id] ?? "#F4F4F6";
  const busy = busyId === integration.id;
  const connectable = isUserConnectable(integration);

  return (
    <div className="integration-card">
      <div
        className="integration-card-icon"
        style={{ background: iconBg, color: iconColor }}
      >
        <IntegrationIcon id={integration.id} />
      </div>

      <div className="integration-card-body">
        <div className="integration-card-title-row">
          <span className="integration-card-name">{integration.name}</span>
        </div>
        {integration.description ? (
          <p className="integration-card-desc">{integration.description}</p>
        ) : null}
      </div>

      {connectable ? (
        <button
          type="button"
          className="integration-add-button"
          aria-label={`Connect ${integration.name}`}
          disabled={busy}
          onClick={() => onToggle(integration)}
        >
          {busy ? <span className="integration-add-busy" /> : <Plus size={18} strokeWidth={2.4} />}
        </button>
      ) : (
        <span className="integration-add-note">Server config</span>
      )}
    </div>
  );
}
