import { ArrowLeft } from "lucide-react";
import type { ChatThread, Route } from "../lib/types";
import { Button, Card } from "../components/Ui";
import { ChatComposer } from "../components/ChatComposer";
import { MessageList } from "../components/MessageList";

export function ChatScreen({
  title,
  subtitle,
  thread,
  draft,
  disabled = false,
  onDraftChange,
  onSend,
  onNavigate
}: {
  title: string;
  subtitle?: string;
  thread: ChatThread | null;
  draft: string;
  disabled?: boolean;
  onDraftChange: (value: string) => void;
  onSend: () => void;
  onNavigate: (route: Route) => void;
}) {
  return (
    <section className="screen-frame">
      <div className="screen-head">
        <div className="screen-title-block">
          <Button variant="ghost" className="back-button mobile-only-inline" onClick={() => onNavigate({ screen: "history" })}>
            <ArrowLeft size={16} />
            Back
          </Button>
          <div className="eyebrow">Guild</div>
          <h1>{title}</h1>
          {subtitle ? <p>{subtitle}</p> : null}
        </div>
      </div>

      {thread ? (
        <Card className="conversation-card">
          <MessageList thread={thread} />
          <ChatComposer
            value={draft}
            placeholder="Ask Hugin anything..."
            disabled={disabled}
            onChange={onDraftChange}
            onSubmit={onSend}
          />
        </Card>
      ) : null}
    </section>
  );
}