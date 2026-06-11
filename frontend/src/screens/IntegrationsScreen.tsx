import { ArrowLeft, CircleCheckBig, CircleAlert, CircleDashed } from "lucide-react";
import { Button, Card, StatusPill } from "../components/Ui";
import type { IntegrationItem, Route } from "../lib/types";

export function IntegrationsScreen({
  integrations,
  onNavigate,
  onOpenDrawer
}: {
  integrations: IntegrationItem[];
  onNavigate: (route: Route) => void;
  onOpenDrawer: () => void;
}) {
  return (
    <section className="screen-frame">
      <div className="screen-head">
        <div className="screen-title-block">
          <Button variant="ghost" className="back-button mobile-only-inline" onClick={() => onNavigate({ screen: "settings" })}>
            <ArrowLeft size={16} />
            Back
          </Button>
          <div className="eyebrow">Guild</div>
          <h1>Integrations</h1>
          <p>Connect the services Guild can use.</p>
        </div>
        <Button variant="ghost" className="mobile-only-inline" onClick={onOpenDrawer}>
          Menu
        </Button>
      </div>

      <Card className="integrations-card">
        {integrations.map((item) => (
          <button
            className="integration-row"
            key={item.id}
            onClick={() => {
              if (item.id === "google-workspace") onNavigate({ screen: "google-workspace" });
            }}
          >
            <div className="integration-icon">
              {item.status === "connected" ? (
                <CircleCheckBig size={18} />
              ) : item.status === "attention" ? (
                <CircleAlert size={18} />
              ) : (
                <CircleDashed size={18} />
              )}
            </div>
            <div className="integration-copy">
              <div className="integration-title">{item.label}</div>
              <div className="integration-subtitle">{item.subtitle}</div>
            </div>
            <StatusPill
              tone={item.status === "connected" ? "green" : item.status === "attention" ? "amber" : "gray"}
              label={item.detail}
            />
          </button>
        ))}
      </Card>
    </section>
  );
}
