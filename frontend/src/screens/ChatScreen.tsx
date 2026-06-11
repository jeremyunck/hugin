import { ArrowLeft, PenSquare } from "lucide-react";
import type { ChatThread, Route } from "../lib/types";
import { Button, Card } from "../components/Ui";
import { ChatComposer } from "../components/ChatComposer";
import { MessageList } from "../components/MessageList";
import { RavenMark } from "../components/RavenMark";

type PromptLink = { label: string; subtitle: string; route: Route };

export function ChatScreen({
  title,
  subtitle,
  thread,
  promptLinks,
  isHome = false,
  draft,
  disabled = false,
  onDraftChange,
  onSend,
  onNavigate,
  onStartNewChat,
  onOpenDrawer
}: {
  title: string;
  subtitle?: string;
  thread: ChatThread | null;
  promptLinks?: PromptLink[];
  isHome?: boolean;
  draft: string;
  disabled?: boolean;
  onDraftChange: (value: string) => void;
  onSend: () => void;
  onNavigate: (route: Route) => void;
  onStartNewChat?: () => void;
  onOpenDrawer: () => void;
}) {
  return (
    <section className="screen-frame">
      <div className="screen-head">
        <div className="screen-title-block">
          {!isHome ? (
            <Button variant="ghost" className="back-button mobile-only-inline" onClick={() => onNavigate({ screen: "chat-home" })}>
              <ArrowLeft size={16} />
              Back
            </Button>
          ) : null}
          <div className="eyebrow">Guild</div>
          <h1>{title}</h1>
          {subtitle ? <p>{subtitle}</p> : null}
        </div>
        <div className="screen-actions">
          {onStartNewChat ? (
            <Button variant="ghost" onClick={onStartNewChat}>
              <PenSquare size={16} />
              New Chat
            </Button>
          ) : null}
          <Button variant="ghost" className="mobile-only-inline" onClick={onOpenDrawer}>
            Menu
          </Button>
        </div>
      </div>

      {isHome ? (
        <Card className="hero-card">
          <div className="hero-mark">
            <RavenMark className="hero-mark-icon" />
          </div>
          <div className="hero-copy">
            <div className="hero-name">Hugin</div>
            <div className="hero-tag">How can I help?</div>
          </div>
        </Card>
      ) : null}

      {promptLinks?.length ? (
        <div className="prompt-grid">
          {promptLinks.map((prompt) => (
            <button className="prompt-card" key={prompt.label} onClick={() => onNavigate(prompt.route)}>
              <div className="prompt-title">{prompt.label}</div>
              <div className="prompt-subtitle">{prompt.subtitle}</div>
            </button>
          ))}
        </div>
      ) : null}

      {thread ? (
        <Card className="conversation-card">
          <MessageList thread={thread} />
          <ChatComposer
            value={draft}
            placeholder={isHome ? "Ask Hugin anything..." : "Ask Hugin anything..."}
            disabled={disabled}
            onChange={onDraftChange}
            onSubmit={onSend}
          />
        </Card>
      ) : isHome ? (
        <Card className="conversation-card">
          <div className="home-composer-hint">Ask Hugin anything, or choose one of the prompts above.</div>
          <ChatComposer
            value={draft}
            placeholder="Ask Hugin anything..."
            disabled={disabled}
            onChange={onDraftChange}
            onSubmit={onSend}
          />
        </Card>
      ) : (
        <Card className="new-chat-card">
          <div className="new-chat-mark">
            <RavenMark className="new-chat-mark-icon" />
          </div>
          <div className="new-chat-title">Start a new conversation</div>
          <p>Your conversation history stays with Guild so you can pick up where you left off.</p>
          {onStartNewChat ? (
            <Button className="new-chat-action" onClick={onStartNewChat}>
              Start New Chat
            </Button>
          ) : null}
        </Card>
      )}
    </section>
  );
}
