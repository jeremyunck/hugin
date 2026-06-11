import { ArrowLeft, RefreshCw, RotateCcw, Trash2, CircleCheckBig, CircleDashed } from "lucide-react";
import { Button, Card, StatusPill } from "../components/Ui";
import type { GoogleWorkspaceState, Route } from "../lib/types";
import { formatDateLabel } from "../services/guildService";

export function IntegrationDetailScreen({
  googleWorkspace,
  onNavigate,
  onRefresh,
  onReconnect,
  onDisconnect,
  onOpenDrawer
}: {
  googleWorkspace: GoogleWorkspaceState;
  onNavigate: (route: Route) => void;
  onRefresh: () => void;
  onReconnect: () => void;
  onDisconnect: () => void;
  onOpenDrawer: () => void;
}) {
  return (
    <section className="screen-frame">
      <div className="screen-head">
        <div className="screen-title-block">
          <Button variant="ghost" className="back-button mobile-only-inline" onClick={() => onNavigate({ screen: "integrations" })}>
            <ArrowLeft size={16} />
            Back
          </Button>
          <div className="eyebrow">Guild</div>
          <h1>Google Workspace</h1>
          <p>Auth status, connected services, and account controls.</p>
        </div>
        <Button variant="ghost" className="mobile-only-inline" onClick={onOpenDrawer}>
          Menu
        </Button>
      </div>

      <Card className="integration-detail-card">
        <div className="integration-detail-top">
          <div className="integration-logo">G</div>
          <div>
            <div className="detail-title">Google Workspace</div>
            <div className="detail-subtitle">{googleWorkspace.accountName}</div>
          </div>
        </div>

        <div className="status-grid">
          <div className="status-block">
            <div className="status-label">Auth status</div>
            <StatusPill
              tone={googleWorkspace.authStatus === "connected" ? "green" : googleWorkspace.authStatus === "attention" ? "amber" : "gray"}
              label={googleWorkspace.authStatus === "connected" ? "Connected" : googleWorkspace.authStatus === "attention" ? "Needs attention" : "Not connected"}
            />
            <div className="status-note">Last refreshed {formatDateLabel(googleWorkspace.lastRefreshedAt)}</div>
          </div>

          <div className="status-block">
            <div className="status-label">Connected services</div>
            <div className="service-list">
              {googleWorkspace.connectedServices.map((service) => (
                <div className="service-row" key={service.label}>
                  <span>{service.label}</span>
                  <span className={`service-state ${service.status}`}>
                    {service.status === "connected" ? <CircleCheckBig size={14} /> : <CircleDashed size={14} />}
                    {service.status === "connected" ? "Connected" : "Not connected"}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="action-stack">
          <Button variant="ghost" onClick={onRefresh}>
            <RefreshCw size={16} />
            Refresh Status
          </Button>
          <Button variant="ghost" onClick={onReconnect}>
            <RotateCcw size={16} />
            Reconnect Google
          </Button>
          <Button variant="danger" onClick={onDisconnect}>
            <Trash2 size={16} />
            Disconnect
          </Button>
        </div>
      </Card>
    </section>
  );
}
