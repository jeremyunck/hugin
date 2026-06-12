import { ChatComposer } from "../components/ChatComposer";
import { RavenMark } from "../components/RavenMark";

export function NewChatScreen({
  draft,
  disabled,
  onDraftChange,
  onSend
}: {
  draft: string;
  disabled?: boolean;
  onDraftChange: (value: string) => void;
  onSend: () => void;
}) {
  return (
    <section className="new-chat-root">
      <div className="new-chat-mark">
        <RavenMark className="new-chat-mark-icon" />
      </div>
      <ChatComposer
        value={draft}
        placeholder="Ask Hugin anything..."
        disabled={disabled}
        onChange={onDraftChange}
        onSubmit={onSend}
      />
    </section>
  );
}