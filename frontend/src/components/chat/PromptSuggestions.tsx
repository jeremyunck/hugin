import type { ChatKind } from "../../lib/types";

type SuggestionGroup = {
  label: string;
  prompts: string[];
};

const CHAT_SUGGESTIONS: SuggestionGroup[] = [
  {
    label: "Conversation",
    prompts: [
      "What can you help me with?",
      "Tell me a fun fact",
      "Help me write a poem",
      "Explain quantum computing simply"
    ]
  }
];

const GITHUB_SUGGESTIONS: SuggestionGroup[] = [
  {
    label: "Explore",
    prompts: [
      "Analyze this codebase",
      "Explain the overall architecture",
      "Summarize what this project does"
    ]
  },
  {
    label: "Improve",
    prompts: [
      "Review the code quality and suggest improvements",
      "Find potential bugs or security issues",
      "Suggest refactoring opportunities",
      "Improve error handling across the codebase"
    ]
  },
  {
    label: "Develop",
    prompts: [
      "Add comprehensive tests for this project",
      "Write documentation for the main modules",
      "Set up a CI/CD pipeline configuration",
      "Optimize the build configuration"
    ]
  }
];

const AGENT_SUGGESTIONS: SuggestionGroup[] = [
  {
    label: "Explore",
    prompts: [
      "List all files in the workspace",
      "Explain the purpose of this project",
      "Show me the directory structure"
    ]
  },
  {
    label: "Create & Edit",
    prompts: [
      "Create a new script to automate this task",
      "Edit the main configuration file",
      "Add a feature to the existing code"
    ]
  }
];

function groupsForKind(kind: ChatKind): SuggestionGroup[] {
  switch (kind) {
    case "github":
      return GITHUB_SUGGESTIONS;
    case "agent":
      return AGENT_SUGGESTIONS;
    default:
      return CHAT_SUGGESTIONS;
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
  const groups = groupsForKind(kind);

  return (
    <div className="prompt-suggestions">
      {groups.map((group) => (
        <div key={group.label} className="prompt-group">
          <span className="prompt-group-label">{group.label}</span>
          <div className="prompt-chips">
            {group.prompts.map((prompt) => (
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
        </div>
      ))}
    </div>
  );
}
