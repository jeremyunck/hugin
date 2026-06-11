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
  return (
    <div className="composer">
      <textarea
        className="composer-input"
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        rows={3}
        disabled={disabled}
      />
      <Button className="composer-send" onClick={onSubmit} disabled={disabled || !value.trim()}>
        <Send size={16} />
        Send
      </Button>
    </div>
  );
}
