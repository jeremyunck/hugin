import { type RefObject } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { ChevronRight, Image as ImageIcon, Wrench } from "lucide-react";

import type { ChatEntry, StreamToolEvent } from "../../lib/types";

function normalizeAssistantMarkdown(content: string) {
  return content.replace(/<br\s*\/?>/gi, "\n");
}

function prettyJson(value: string) {
  const trimmed = value.trim();
  if (!trimmed) return "";
  try {
    return JSON.stringify(JSON.parse(trimmed), null, 2);
  } catch {
    return value;
  }
}

/**
 * A single tool call rendered inline in the transcript. Collapsed it shows just the tool name and a
 * status dot; expanded it reveals the call input and the result/output it produced.
 */
function ToolCallEntry({ tool }: { tool: StreamToolEvent }) {
  const pending = !tool.finishedAt;
  const status = pending ? "running" : tool.error ? "error" : "completed";
  return (
    <div className="message-row message-row-tool fade-in">
      <details className="tool-call" open={pending}>
        <summary className="tool-call-summary">
          <ChevronRight className="tool-call-caret" size={14} strokeWidth={2.5} />
          <Wrench size={13} strokeWidth={2} className="tool-call-icon" />
          <span className="tool-call-name">{tool.name}</span>
          <span className={`tool-call-status tool-call-status-${status}`} />
        </summary>
        <div className="tool-call-body">
          <div className="tool-call-section-label">Input</div>
          <pre className="tool-call-pre">{prettyJson(tool.args) || "—"}</pre>
          <div className="tool-call-section-label">Output</div>
          <pre className="tool-call-pre">{pending ? "Running…" : tool.result || "—"}</pre>
        </div>
      </details>
    </div>
  );
}

function TypingDots() {
  return (
    <span className="typing-dots">
      <span className="dot" />
      <span className="dot" />
      <span className="dot" />
    </span>
  );
}

/**
 * Renders the main chat transcript: user and assistant messages, plus tool calls projected inline as
 * expandable cards and any system notices (e.g. conversation compaction). Run lifecycle and other
 * low-level activity stay out of the transcript.
 */
export function MessageList({
  entries,
  busy,
  listRef
}: {
  entries: ChatEntry[];
  busy: boolean;
  listRef: RefObject<HTMLDivElement>;
}) {
  return (
    <div ref={listRef} className="messages">
      {entries.map((entry) => {
        if (entry.type === "user") {
          return (
            <div key={entry.id} className="message-row message-row-user fade-in">
              <div className="message-bubble message-bubble-user">
                {entry.attachments?.map((attachment) =>
                  attachment.dataUrl ? (
                    <img
                      key={`${entry.id}-${attachment.name}`}
                      src={attachment.dataUrl}
                      alt={attachment.name}
                      className="message-image"
                    />
                  ) : (
                    <div key={`${entry.id}-${attachment.name}`} className="message-attachment-placeholder">
                      <ImageIcon size={14} strokeWidth={2} />
                      <span>{attachment.name}</span>
                    </div>
                  )
                )}
                {entry.content ? <div>{entry.content}</div> : null}
              </div>
            </div>
          );
        }

        if (entry.type === "tool") {
          return <ToolCallEntry key={entry.id} tool={entry.tool} />;
        }

        if (entry.type === "notice") {
          return (
            <div key={entry.id} className="message-row message-row-notice fade-in">
              <div className="message-notice">{entry.content}</div>
            </div>
          );
        }

        if (entry.type !== "assistant") {
          return null;
        }

        const empty = !entry.content && !entry.reasoning;
        return (
          <div key={entry.id} className="message-row message-row-assistant fade-in">
            <div className="assistant-response">
              {empty && busy ? (
                <TypingDots />
              ) : (
                <>
                  {entry.reasoning ? <div className="assistant-reasoning">{entry.reasoning}</div> : null}
                  {entry.content ? (
                    <ReactMarkdown remarkPlugins={[remarkGfm]}>{normalizeAssistantMarkdown(entry.content)}</ReactMarkdown>
                  ) : null}
                </>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
