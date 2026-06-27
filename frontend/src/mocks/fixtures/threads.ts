import type { ChatThread } from "../../lib/types";
import type { ChatEvent } from "../../services/apiClient";
import { daysAgo, minutesAgo } from "./auth";

/**
 * Thread metadata used to populate the History list, the sidebar, and the active-thread selection.
 * The full transcript for a thread is projected from its mocked chat-event log (see
 * {@link mockEventsBySession}) exactly the way the real app rebuilds a thread from the server log.
 */
function thread(partial: Partial<ChatThread> & Pick<ChatThread, "id" | "title" | "kind" | "updatedAt">): ChatThread {
  return {
    sandboxId: undefined,
    repoFullName: undefined,
    repoName: undefined,
    branchName: undefined,
    modelId: "anthropic/claude-opus-4.8",
    reasoningEffort: "medium",
    createdAt: partial.updatedAt,
    entries: [],
    activities: [],
    lastSeq: 0,
    run: null,
    connectionStatus: "idle",
    hasHistory: true,
    ...partial
  };
}

/** A plain chat (kind "chat" → the "purechat" screen). */
export const MOCK_PURECHAT_THREAD_ID = "thread-chat-1";
/** A GitHub project chat (kind "github" → the "chat" screen, with a workspace panel). */
export const MOCK_GITHUB_THREAD_ID = "thread-github-1";
/** A server agent chat (kind "agent"). */
export const MOCK_AGENT_THREAD_ID = "thread-agent-1";

export const mockThreads: ChatThread[] = [
  thread({
    id: MOCK_PURECHAT_THREAD_ID,
    title: "Compare charting libraries for a dashboard",
    kind: "chat",
    updatedAt: minutesAgo(8)
  }),
  thread({
    id: MOCK_GITHUB_THREAD_ID,
    title: "ada-lovelace/bouw-demo (main)",
    kind: "github",
    sandboxId: "sandbox-bouw-demo",
    repoFullName: "ada-lovelace/bouw-demo",
    repoName: "bouw-demo",
    branchName: "main",
    modelId: "anthropic/claude-sonnet-4.6",
    updatedAt: minutesAgo(22)
  }),
  thread({
    id: MOCK_AGENT_THREAD_ID,
    title: "Summarise this week's research notes",
    kind: "agent",
    updatedAt: minutesAgo(40)
  }),
  thread({
    id: "thread-chat-2",
    title: "Explain the difference between debounce and throttle",
    kind: "chat",
    updatedAt: daysAgo(1)
  }),
  thread({
    id: "thread-chat-3",
    title: "Draft a release note for v0.4.0",
    kind: "chat",
    updatedAt: daysAgo(2)
  }),
  thread({
    id: "thread-github-2",
    title: "ada-lovelace/analytical-engine (release/1.x)",
    kind: "github",
    sandboxId: "sandbox-analytical-engine",
    repoFullName: "ada-lovelace/analytical-engine",
    repoName: "analytical-engine",
    branchName: "release/1.x",
    updatedAt: daysAgo(4)
  })
];

/**
 * Fluent, incrementing-seq builder so each mocked event log reads like a real ordered backend
 * stream. `seq` is the per-session monotonic key the projection reducer dedups on; `createdAt`
 * marches steadily backwards from a base so the transcript ordering is stable across runs.
 */
type Builder = {
  user(content: string): Builder;
  assistant(content: string, reasoning?: string): Builder;
  tool(name: string, args: string, result: string): Builder;
  done(): ChatEvent[];
};

function conversation(threadId: string, baseMinutes: number): Builder {
  let seq = 0;
  let offset = baseMinutes;
  const events: ChatEvent[] = [];
  const at = () => minutesAgo((offset -= 0.2));
  const push = (type: string, extra: Partial<ChatEvent>) => {
    events.push({ id: `${threadId}-e${++seq}`, seq, type, createdAt: at(), ...extra });
  };

  const builder: Builder = {
    user(content: string) {
      push("user_message_created", { messageId: `${threadId}-m${seq + 1}`, content });
      return builder;
    },
    tool(name: string, args: string, result: string) {
      const callId = `${threadId}-call-${seq + 1}`;
      push("tool_call_started", { metadata: { callId, name, args } });
      push("tool_call_completed", { metadata: { callId, name, result } });
      return builder;
    },
    assistant(content: string, reasoning?: string) {
      const messageId = `${threadId}-a${seq + 1}`;
      push("assistant_message_started", { messageId });
      if (reasoning) push("assistant_reasoning", { messageId, content: reasoning });
      push("assistant_token", { messageId, content });
      push("assistant_message_completed", { messageId });
      return builder;
    },
    done() {
      return events;
    }
  };
  return builder;
}

function pureChatLog(): ChatEvent[] {
  return conversation(MOCK_PURECHAT_THREAD_ID, 9)
    .user("I'm building an analytics dashboard in React. Which charting library should I use?")
    .assistant(
      [
        "It depends on how much customisation you need, but here's a quick comparison of the three I'd shortlist:",
        "",
        "| Library | Bundle size | Strengths | Watch out for |",
        "| --- | --- | --- | --- |",
        "| **Recharts** | ~95 kB | Declarative, composable React components | Limited for very custom visuals |",
        "| **visx** | pick-what-you-need | Low-level D3 primitives, fully themeable | More code to write |",
        "| **Chart.js** | ~75 kB | Battle-tested, great defaults | Canvas-based, less React-native |",
        "",
        "For a dashboard that mostly needs **line, bar, and area charts**, I'd start with **Recharts** — you'll get a polished result quickly and can drop down to visx later if you hit a wall.",
        "",
        "Want a small Recharts example wired to your data shape?"
      ].join("\n")
    )
    .user("Yes please — a responsive line chart.")
    .assistant(
      [
        "Here's a responsive line chart that fills its container:",
        "",
        "```tsx",
        "import { ResponsiveContainer, LineChart, Line, XAxis, YAxis, Tooltip } from \"recharts\";",
        "",
        "export function RevenueChart({ data }: { data: { month: string; revenue: number }[] }) {",
        "  return (",
        "    <ResponsiveContainer width=\"100%\" height={320}>",
        "      <LineChart data={data}>",
        "        <XAxis dataKey=\"month\" />",
        "        <YAxis />",
        "        <Tooltip />",
        "        <Line type=\"monotone\" dataKey=\"revenue\" strokeWidth={2} dot={false} />",
        "      </LineChart>",
        "    </ResponsiveContainer>",
        "  );",
        "}",
        "```",
        "",
        "Wrap it in a sized parent and it'll resize with the layout. Let me know if you'd like the tooltip styled to match your theme."
      ].join("\n"),
      "The user wants a responsive line chart. Recharts' ResponsiveContainer is the idiomatic way to do this, so I'll give a minimal but complete component."
    )
    .done();
}

function gitHubLog(): ChatEvent[] {
  return conversation(MOCK_GITHUB_THREAD_ID, 23)
    .user("The onboarding wizard skips the workspace step on mobile. Can you find and fix it?")
    .assistant(
      "Let me look at the onboarding wizard component to see how the steps are filtered."
    )
    .tool(
      "read_file",
      "{\"path\":\"src/components/OnboardingWizard.tsx\"}",
      "export function OnboardingWizard() {\n  const steps = STEPS.filter((s) => !s.desktopOnly);\n  // ...\n}"
    )
    .assistant(
      [
        "Found it. The `workspace` step is flagged `desktopOnly: true`, and the wizard filters those out on every viewport — not just desktop. The fix is to only drop desktop-only steps when we're actually on a small screen:",
        "",
        "```diff",
        "- const steps = STEPS.filter((s) => !s.desktopOnly);",
        "+ const steps = STEPS.filter((s) => !(s.desktopOnly && isMobile));",
        "```",
        "",
        "I've also added a regression test in `tests/onboarding.test.ts` that asserts the workspace step is present on a 390px-wide viewport. Want me to open a pull request against `main`?"
      ].join("\n")
    )
    .done();
}

function agentLog(): ChatEvent[] {
  return conversation(MOCK_AGENT_THREAD_ID, 41)
    .user("Summarise the research notes in ~/notes into a short report.")
    .assistant(
      [
        "I read `notes/research-summary.md` and `notes/todo.md` and wrote a condensed report to `report.md`. Highlights:",
        "",
        "- **Charting**: Recharts is the recommended default; visx is the escape hatch for bespoke visuals.",
        "- **Performance**: virtualise lists over ~200 rows; memoise chart data transforms.",
        "- **Open questions**: whether to support real-time streaming updates in v1.",
        "",
        "The full write-up is in `report.md` — let me know if you'd like it as a different format."
      ].join("\n")
    )
    .done();
}

export const mockEventsBySession: Record<string, ChatEvent[]> = {
  [MOCK_PURECHAT_THREAD_ID]: pureChatLog(),
  [MOCK_GITHUB_THREAD_ID]: gitHubLog(),
  [MOCK_AGENT_THREAD_ID]: agentLog()
};

export function mockEventsFor(sessionId: string, afterSeq = 0): ChatEvent[] {
  const events = mockEventsBySession[sessionId] ?? [];
  return afterSeq > 0 ? events.filter((event) => event.seq > afterSeq) : events;
}
