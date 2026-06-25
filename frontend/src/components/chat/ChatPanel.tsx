import { type RefObject } from "react";

import type { ApprovalDecision, ChatAttachment, ChatEntry, ChatKind, ModelOption } from "../../lib/types";
import { MessageList } from "./MessageList";
import { Composer } from "./Composer";
import { PromptSuggestions } from "./PromptSuggestions";

function Greeting({ name, kind, disabled, onSend }: { name: string; kind: ChatKind; disabled: boolean; onSend: (prompt?: string) => void }) {
  return (
    <div className="greeting">
      <h1>Hi {name}! 👋</h1>
      <p>How can I help you today?</p>
      <PromptSuggestions
        kind={kind}
        disabled={disabled}
        onSelect={(prompt) => onSend(prompt)}
      />
    </div>
  );
}

/**
 * Top-level chat surface: the empty-state greeting or the transcript + activity, plus the composer.
 * Pure presentation — all state and engine logic lives in the chat session store.
 */
export function ChatPanel(props: {
  name: string;
  kind: ChatKind;
  entries: ChatEntry[];
  busy: boolean;
  running: boolean;
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
  onStop: () => void;
  onApproval: (approvalId: string, decision: ApprovalDecision) => void;
}) {
  const fresh = props.entries.length === 0;
  return (
    <>
      {fresh ? (
        <div className="chat-body">
          <Greeting name={props.name} kind={props.kind} disabled={props.busy || props.models.length === 0} onSend={props.onSend} />
        </div>
      ) : (
        <div className="chat-stack">
          <MessageList
            entries={props.entries}
            busy={props.busy}
            listRef={props.listRef}
            onApproval={props.onApproval}
          />
        </div>
      )}
      <Composer
        value={props.draft}
        disabled={props.busy || props.models.length === 0}
        busy={props.running}
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
        onStop={props.onStop}
      />
    </>
  );
}
