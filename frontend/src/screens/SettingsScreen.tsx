import { ArrowLeft, ChevronRight } from "lucide-react";
import type { ReactNode } from "react";
import { Button, Card } from "../components/Ui";
import type { Route } from "../lib/types";

export function SettingsScreen({
  onNavigate,
  onOpenClearHistory
}: {
  onNavigate: (route: Route) => void;
  onOpenClearHistory: (route: Route) => void;
}) {
  return (
    <section className="screen-frame">
      <div className="screen-head">
        <div className="screen-title-block">
          <Button variant="ghost" className="back-button mobile-only-inline" onClick={() => onNavigate({ screen: "new-chat" })}>
            <ArrowLeft size={16} />
            Back
          </Button>
          <div className="eyebrow">Guild</div>
          <h1>Settings</h1>
          <p>General preferences, integrations, and data controls.</p>
        </div>
      </div>

      <Card className="settings-card">
        <SettingsSection title="General">
          <SettingsRow label="Language" value="English" />
          <SettingsAction label="Clear chat history" onClick={() => onOpenClearHistory({ screen: "settings" })} />
        </SettingsSection>

        <SettingsSection title="Appearance">
          <SettingsNav label="Theme" value="Light" onClick={() => onNavigate({ screen: "appearance" })} />
          <SettingsNav label="Text size" value="Medium" onClick={() => onNavigate({ screen: "appearance" })} />
          <SettingsNav label="Reduce motion" value="Off" onClick={() => onNavigate({ screen: "appearance" })} />
        </SettingsSection>

        <SettingsSection title="Data">
          <SettingsNav label="Data & Privacy" value="Open" onClick={() => onNavigate({ screen: "data-privacy" })} />
        </SettingsSection>

        <SettingsSection title="Integrations">
          <SettingsNav label="Connected services" value="4 connected" onClick={() => onNavigate({ screen: "integrations" })} />
        </SettingsSection>

        <SettingsSection title="About">
          <SettingsRow label="Version" value="1.0.0" />
        </SettingsSection>
      </Card>
    </section>
  );
}

function SettingsSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="settings-section">
      <div className="settings-section-title">{title}</div>
      <div className="settings-list">{children}</div>
    </div>
  );
}

function SettingsRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="settings-row static">
      <div>{label}</div>
      <div className="settings-value">{value}</div>
    </div>
  );
}

function SettingsNav({ label, value, onClick }: { label: string; value: string; onClick: () => void }) {
  return (
    <button className="settings-row" onClick={onClick}>
      <div>{label}</div>
      <div className="settings-value">
        {value}
        <ChevronRight size={16} />
      </div>
    </button>
  );
}

function SettingsAction({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button className="settings-row danger" onClick={onClick}>
      <div>{label}</div>
      <ChevronRight size={16} />
    </button>
  );
}
