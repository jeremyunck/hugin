import { useEffect, useRef } from "react";
import { Send } from "lucide-react";
import { Button } from "./Ui";

export function ChatComposer({
  value,
  placeholder,
  disabled,
  onChange,
  onSubmit
}: {
  value: string;
  placeholder: string;
  disabled?: boolean;
  onChange: (value: string) => void;
  onSubmit: () => void;
}) {
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const canSubmit = !disabled && value.trim().length > 0;

  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "0px";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 220)}px`;
  }, [value]);

  return (
    <div className="composer">
      <textarea
        ref={textareaRef}
        className="composer-input"
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing && canSubmit) {
            event.preventDefault();
            onSubmit();
          }
        }}
        rows={3}
        disabled={disabled}
      />
      <Button className="composer-send" onClick={onSubmit} disabled={!canSubmit}>
        <Send size={16} />
        Send
      </Button>
    </div>
  );
}
