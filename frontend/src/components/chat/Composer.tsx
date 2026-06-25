import { ChevronDown, Gauge, Image as ImageIcon, Send, Sparkles, Square, X } from "lucide-react";

import type { ChatAttachment, ModelOption } from "../../lib/types";
import { defaultReasoningFor, formatBytes, labelReasoning } from "../../lib/format";

const INK = "#1C1F23";
const MUTED = "#8B9099";

/**
 * The message composer: text input, image attachment, model/reasoning selectors, and send. Its
 * disabled state is driven by the active thread's run state (passed in via `disabled`), so a
 * failed/completed/cancelled run immediately re-enables sending. While a run is in flight (`busy`)
 * the send button is replaced by a stop button (`onStop`) so the user can always interrupt a run
 * rather than being locked out of the thread.
 */
import { useRef, useEffect } from "react";

export function Composer(props: {
  value: string;
  disabled: boolean;
  busy: boolean;
  attachment: ChatAttachment | null;
  models: ModelOption[];
  selectedModelId?: string;
  selectedReasoning?: string;
  onChange: (value: string) => void;
  onModelChange: (value: string) => void;
  onReasoningChange: (value: string) => void;
  onPickImage: () => void;
  onClearImage: () => void;
  onSend: () => void;
  onStop: () => void;
}) {
  const {
    value,
    disabled,
    busy,
    attachment,
    models,
    selectedModelId,
    selectedReasoning,
    onChange,
    onModelChange,
    onReasoningChange,
    onPickImage,
    onClearImage,
    onSend,
    onStop
  } = props;

  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  // Adjust height to fit content up to a max height
  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "0px";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 220)}px`;
  }, [value]);
  const activeModel = models.find((model) => model.id === selectedModelId) ?? models[0];
  const reasoningOptions = activeModel?.reasoningOptions ?? [];

  return (
    <div className="input-wrap">
      {attachment ? (
        <div className="composer-attachment">
          {attachment.dataUrl ? <img src={attachment.dataUrl} alt={attachment.name} className="composer-attachment-thumb" /> : null}
          <div className="composer-attachment-copy">
            <span>{attachment.name}</span>
            <span>{formatBytes(attachment.size)}</span>
          </div>
          <button type="button" className="composer-attachment-remove" onClick={onClearImage} aria-label="Remove image">
            <X size={14} strokeWidth={2.4} />
          </button>
        </div>
      ) : null}
      <div className="input-bar">
        <button type="button" className="input-bar-attach" onClick={onPickImage} disabled={disabled} aria-label="Add image">
          <ImageIcon size={18} strokeWidth={2} color={INK} />
        </button>
        <textarea
          ref={textareaRef}
          className="composer-input"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey && !disabled && (value.trim() || attachment)) {
              event.preventDefault();
              onSend();
            }
          }}
          rows={3}
          disabled={disabled}
          placeholder={attachment ? "Ask about this image..." : "Message Hugin…"}
        />
        {busy ? (
          <button type="button" className="input-bar-send" onClick={onStop} aria-label="Stop run">
            <Square size={16} strokeWidth={2.4} color="#fff" fill="#fff" />
          </button>
        ) : (
          <button
            type="button"
            className="input-bar-send"
            onClick={onSend}
            disabled={disabled || (!value.trim() && !attachment)}
            aria-label="Send message"
          >
            <Send size={17} strokeWidth={2} color="#fff" />
          </button>
        )}
      </div>
      <div className="composer-controls">
        <label className="composer-select">
          <span>Model</span>
          <div className="composer-field">
            <Sparkles className="composer-field-icon" size={15} strokeWidth={2} color={MUTED} />
            <select
              value={activeModel?.id ?? ""}
              onChange={(event) => onModelChange(event.target.value)}
              disabled={disabled || models.length === 0}
            >
              {models.length === 0 ? <option value="">No enabled models</option> : null}
              {models.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.name}
                </option>
              ))}
            </select>
            <ChevronDown className="composer-field-caret" size={15} strokeWidth={2} color={MUTED} />
          </div>
        </label>
        <label className="composer-select">
          <span>Reasoning</span>
          <div className="composer-field">
            <Gauge className="composer-field-icon" size={15} strokeWidth={2} color={MUTED} />
            <select
              value={reasoningOptions.length ? (selectedReasoning ?? defaultReasoningFor(activeModel) ?? reasoningOptions[0]) : ""}
              onChange={(event) => onReasoningChange(event.target.value)}
              disabled={disabled || reasoningOptions.length === 0}
            >
              {reasoningOptions.length === 0 ? <option value="">Unavailable</option> : null}
              {reasoningOptions.map((option) => (
                <option key={option} value={option}>
                  {labelReasoning(option)}
                </option>
              ))}
            </select>
            <ChevronDown className="composer-field-caret" size={15} strokeWidth={2} color={MUTED} />
          </div>
        </label>
      </div>
      <p className="input-note">Hugin can make mistakes. Please verify important information.</p>
    </div>
  );
}
