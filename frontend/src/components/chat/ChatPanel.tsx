import { type RefObject } from "react";

import type { ChatAttachment, ChatEntry, ModelOption } from "../../lib/types";
import { MessageList } from "./MessageList";
import { Composer } from "./Composer";

const CHIPS = [
  ["Summarize a document", "Summarize a document for me."],
  ["Analyze data", "Analyze this dataset and show key trends."],
  ["Write code", "Write a Python script to clean a CSV file."],
  ["Brainstorm ideas", "Brainstorm ideas for a product launch."],
  ["Show me tips", "Show me tips for getting the most out of Hugin."]
] as const;

function Greeting({ name, onChip }: { name: string; onChip: (prompt: string) => void }) {
  return (
    <div className="greeting">
      <h1>Hi {name}! 👋</h1>
      <p>How can I help you today?</p>
      <div className="chip-list">
        {CHIPS.map(([label, prompt]) => (
          <button key={label} type="button" className="chip" onClick={() => onChip(prompt)}>
            {label}
          </button>
        ))}
      </div>
    </div>
  );
}

/**
 * Top-level chat surface: the empty-state greeting or the transcript + activity, plus the composer.
 * Pure presentation — all state and engine logic lives in the chat session store.
 */
export function ChatPanel(props: {
  name: string;
  entries: ChatEntry[];
  busy: boolean;
  listRef: RefObject<HTMLDivElement>;
  draft: string;
  attachment: ChatAttachment | null;
  models: ModelOption[];
  selectedModelId?: string;
  selectedReasoning?: string;
  onDraftChange: (value: string) => void;
  onModelChange: (value: string) => void;
  onReasoningChange: (value: string) => void;
  onPickImage: () => void;
  onClearImage: () => void;
  onSend: (prompt?: string) => void;
}) {
  const fresh = props.entries.length === 0;
  return (
    <>
      {fresh ? (
        <div className="chat-body">
          <Greeting name={props.name} onChip={(prompt) => props.onSend(prompt)} />
        </div>
      ) : (
        <div className="chat-stack">
          <MessageList entries={props.entries} busy={props.busy} listRef={props.listRef} />
        </div>
      )}
      <Composer
        value={props.draft}
        disabled={props.busy || props.models.length === 0}
        attachment={props.attachment}
        models={props.models}
        selectedModelId={props.selectedModelId}
        selectedReasoning={props.selectedReasoning}
        onChange={props.onDraftChange}
        onModelChange={props.onModelChange}
        onReasoningChange={props.onReasoningChange}
        onPickImage={props.onPickImage}
        onClearImage={props.onClearImage}
        onSend={() => props.onSend()}
      />
    </>
  );
}
