import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { RavenMark } from "./RavenMark";
import type { ChatThread } from "../lib/types";
import { formatDateLabel } from "../services/guildService";

export function MessageList({ thread }: { thread: ChatThread }) {
  return (
    <div className="message-list">
      {thread.messages.map((message) => (
        <article key={message.id} className={`message-card ${message.role}`}>
          <div className="message-head">
            <div className="message-avatar">{message.role === "assistant" ? <RavenMark className="message-mark" /> : "J"}</div>
            <div>
              <div className="message-role">{message.role === "assistant" ? "Hugin" : "You"}</div>
              <div className="message-time">{formatDateLabel(message.createdAt)}</div>
            </div>
          </div>
          <div className="message-body">
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{message.content}</ReactMarkdown>
          </div>
        </article>
      ))}
    </div>
  );
}
