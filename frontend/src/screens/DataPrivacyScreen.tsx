import { ArrowLeft, Download, Trash2 } from "lucide-react";
import { Button, Card } from "../components/Ui";
import type { GuildState, Route } from "../lib/types";
import { downloadStateSnapshot } from "../services/guildService";

export function DataPrivacyScreen({
  state,
  onClearHistory,
  onNavigate
}: {
  state: GuildState;
  onClearHistory: (route: Route) => void;
  onNavigate: (route: Route) => void;
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
          <h1>Data & Privacy</h1>
          <p>See what lives in the app and clear it when needed.</p>
        </div>
      </div>

      <Card className="privacy-card">
        <section className="privacy-block">
          <h2>What Guild stores</h2>
          <p>
            Conversation threads, appearance preferences, and integration status are stored locally so the app can restore
            your session on refresh.
          </p>
        </section>

        <section className="privacy-block">
          <h2>What stays local</h2>
          <p>
            This frontend keeps the mock state in browser storage. There is no hidden placeholder API for these screens.
          </p>
        </section>

        <section className="privacy-block">
          <h2>Actions</h2>
          <div className="action-stack">
            <Button variant="ghost" onClick={() => downloadStateSnapshot(state)}>
              <Download size={16} />
              Download data snapshot
            </Button>
            <Button variant="danger" onClick={() => onClearHistory({ screen: "data-privacy" })}>
              <Trash2 size={16} />
              Clear chat history
            </Button>
          </div>
        </section>
      </Card>
    </section>
  );
}
