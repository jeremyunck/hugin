import type { ChatKind } from "../../lib/types";

const CHAT_PROMPTS = [
  "What can you help me with?",
  "Tell me a fun fact",
  "Help me write a poem"
];

const GITHUB_PROMPTS = [
  "Analyze this codebase",
  "Explain the overall architecture",
  "Summarize what this project does"
];

const AGENT_PROMPTS = [
  "List all files in the workspace",
  "Explain the purpose of this project",
  "Show me the directory structure"
];

function promptsForKind(kind: ChatKind): string[] {
  switch (kind) {
    case "github":
      return GITHUB_PROMPTS;
    case "agent":
      return AGENT_PROMPTS;
    default:
      return CHAT_PROMPTS;
  }
}

export function PromptSuggestions({
  kind,
  disabled,
  onSelect
}: {
  kind: ChatKind;
  disabled: boolean;
  onSelect: (prompt: string) => void;
}) {
  const prompts = promptsForKind(kind);

  return (
    <div className="prompt-suggestions">
      {prompts.map((prompt) => (
        <button
          key={prompt}
          type="button"
          className="prompt-chip"
          disabled={disabled}
          onClick={() => onSelect(prompt)}
        >
          {prompt}
        </button>
      ))}
    </div>
  );
}
