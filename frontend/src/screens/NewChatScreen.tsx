import { ArrowLeft, MessageSquarePlus } from "lucide-react";
import { Button, Card } from "../components/Ui";
import type { Route } from "../lib/types";
import { RavenMark } from "../components/RavenMark";

export function NewChatScreen({
  onNavigate,
  onStartNewChat,
  onOpenDrawer
}: {
  onNavigate: (route: Route) => void;
  onStartNewChat: () => void;
  onOpenDrawer: () => void;
}) {
  return (
    <section className="screen-frame">
      <div className="screen-head">
        <div className="screen-title-block">
          <Button variant="ghost" className="back-button mobile-only-inline" onClick={() => onNavigate({ screen: "chat-home" })}>
            <ArrowLeft size={16} />
            Back
          </Button>
          <div className="eyebrow">Guild</div>
          <h1>New Chat</h1>
          <p>Start a fresh conversation with Hugin.</p>
        </div>
        <Button variant="ghost" className="mobile-only-inline" onClick={onOpenDrawer}>
          Menu
        </Button>
      </div>

      <Card className="new-chat-card large">
        <div className="new-chat-mark">
          <RavenMark className="new-chat-mark-icon" />
        </div>
        <div className="new-chat-title">Start a new conversation</div>
        <p>Your conversation history stays with Guild so you can pick up where you left off.</p>
        <Button className="new-chat-action" onClick={onStartNewChat}>
          <MessageSquarePlus size={16} />
          Start New Chat
        </Button>
      </Card>
    </section>
  );
}
