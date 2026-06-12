import type { ChatThread } from "../lib/types";
import { Card } from "../components/Ui";
import { ChatComposer } from "../components/ChatComposer";
import { MessageList } from "../components/MessageList";

export function ChatScreen({
  thread,
  draft,
  disabled = false,
  onDraftChange,
  onSend
}: {
  thread: ChatThread | null;
  draft: string;
  disabled?: boolean;
  onDraftChange: (value: string) => void;
  onSend: () => void;
}) {
  return (
    <section className="screen-frame">
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
