import { useState } from "react";
import {
  Activity,
  ArrowLeft,
  CheckCircle,
  ChevronRight,
  Github,
  Globe,
  Plus,
  Search,
  ShieldCheck,
  type LucideIcon,
} from "lucide-react";

import type { Integration } from "../lib/types";

/* ------------------------------------------------------------------ */
/*  Integration icon map                                               */
/* ------------------------------------------------------------------ */

const ICON_MAP: Record<string, LucideIcon> & { __brand?: string } = {
  google: Globe,
  github: Github,
  web_search: Search,
} as Record<string, LucideIcon>;

const LABEL_COLOR_MAP: Record<string, string> = {
  google: "#4285F4",
  github: "#1C1F23",
  web_search: "#8B9099",
};

const BG_COLOR_MAP: Record<string, string> = {
  google: "#E8F0FE",
  github: "#F0F0F1",
  web_search: "#F4F4F6",
};

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

/** Friendly summary of what access an integration's auth grants. */
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

/** Number of tools an integration contributes, e.g. "14 tools". */
function toolCountLabel(integration: Integration): string {
  const count = integration.tools.length;
  return `${count} tool${count === 1 ? "" : "s"}`;
}

/** web_search is configured server-side via an API key, so it can't be connected from the UI. */
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
  onBack: () => void;
  onToggle: (integration: Integration) => void;
  onReconnect: (integration: Integration) => void;
}) {
  const { integrations, loading, error, busyId, onBack, onToggle, onReconnect } = props;
  const [tab, setTab] = useState<Tab>("mine");

  const connected = integrations.filter((i) => i.connected);
  const available = integrations.filter((i) => !i.connected);
  const allHealthy = connected.length > 0;

  return (
    <div className="integrations-screen">
      {/* ── Header ─────────────────────────────── */}
      <div className="back-row">
        <button
          type="button"
          className="icon-button back-button"
          onClick={onBack}
          aria-label="Back"
        >
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">Integrations</h1>
        <p className="integration-subtitle">
          Hugin can use these tools to find information, take action, and get things done for you.
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
              <div className="integrations-section-sub">Tools Hugin can use right now</div>
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
                Connect more tools to expand what Hugin can do
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
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Connected integration card                                         */
/* ------------------------------------------------------------------ */

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
  const Icon = ICON_MAP[integration.id] ?? CheckCircle;
  const iconColor = LABEL_COLOR_MAP[integration.id] ?? "#1C1F23";
  const iconBg = BG_COLOR_MAP[integration.id] ?? "#F4F4F6";
  const busy = busyId === integration.id;
  const connectable = isUserConnectable(integration);

  return (
    <div className="integration-card">
      {/* Left: icon */}
      <div
        className="integration-card-icon"
        style={{ background: iconBg, color: iconColor }}
      >
        <Icon size={26} strokeWidth={1.7} />
      </div>

      {/* Right: content */}
      <div className="integration-card-body">
        {/* Title row */}
        <div className="integration-card-title-row">
          <span className="integration-card-name">{integration.name}</span>
          <span className="integration-card-badge">
            <CheckCircle size={13} strokeWidth={2.5} />
            Connected
          </span>
          <ChevronRight size={18} strokeWidth={2} color="#c4c7cd" className="integration-card-chevron" />
        </div>

        {/* Description */}
        {integration.description ? (
          <p className="integration-card-desc">{integration.description}</p>
        ) : null}

        {/* Meta row */}
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

        {/* Actions */}
        {connectable ? (
          <div className="integration-card-actions">
            {integration.reconnectable ? (
              <button
                type="button"
                className="integration-link-action"
                disabled={busy}
                onClick={() => onReconnect(integration)}
              >
                {busy ? "Working…" : "Reconnect"}
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
  const Icon = ICON_MAP[integration.id] ?? Globe;
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
        <Icon size={26} strokeWidth={1.7} />
      </div>

      <div className="integration-card-body">
        <div className="integration-card-title-row">
          <span className="integration-card-name">{integration.name}</span>
        </div>
        {integration.description ? (
          <p className="integration-card-desc">{integration.description}</p>
        ) : null}
      </div>

      {/* Connect affordance */}
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
