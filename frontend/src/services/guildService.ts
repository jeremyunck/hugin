import type { AppearanceSettings, ChatMessage, ChatThread, GuildState, IntegrationItem } from "../lib/types";

const STORAGE_KEY = "guild-app-state-v1";

function nowIso() {
  return new Date().toISOString();
}

function minutesAgo(minutes: number) {
  return new Date(Date.now() - minutes * 60_000).toISOString();
}

function hoursAgo(hours: number) {
  return new Date(Date.now() - hours * 60 * 60_000).toISOString();
}

function daysAgo(days: number) {
  return new Date(Date.now() - days * 24 * 60 * 60_000).toISOString();
}

function uid(prefix = "id") {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
  }
  return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
}

function threadMessage(role: ChatMessage["role"], content: string, createdAt: string): ChatMessage {
  return { id: uid(role), role, content, createdAt };
}

function makeThread(
  id: string,
  title: string,
  createdAt: string,
  updatedAt: string,
  source: ChatThread["source"],
  messages: ChatMessage[]
): ChatThread {
  return { id, title, createdAt, updatedAt, source, messages };
}

function buildSeedThreads(): ChatThread[] {
  return [
    makeThread(
      "check-server-status",
      "Check Server Status",
      daysAgo(2),
      hoursAgo(3),
      "scenario",
      [
        threadMessage("user", "What is the status of my production servers?", hoursAgo(3)),
        threadMessage(
          "assistant",
          "All systems are operational.\n\n- Web servers: 4 healthy\n- API servers: 3 healthy\n- Database: 1 healthy\n- Cache: 2 healthy\n\nEverything is running normally.\n\nLast updated: just now",
          hoursAgo(3)
        )
      ]
    ),
    makeThread(
      "summarize-emails",
      "Summarize Emails",
      daysAgo(2),
      hoursAgo(8),
      "scenario",
      [
        threadMessage("user", "Summarize my unread emails from today", hoursAgo(8)),
        threadMessage(
          "assistant",
          "Here is a summary of your unread emails from today:\n\n- Project update from Alex\n- Meeting request with Sarah\n- Invoice approval needed\n- New design assets shared by the team",
          hoursAgo(8)
        )
      ]
    ),
    makeThread(
      "research-ai-agents",
      "Research on AI Agents",
      daysAgo(3),
      hoursAgo(11),
      "scenario",
      [
        threadMessage("user", "What are the latest advances in AI agents?", hoursAgo(11)),
        threadMessage(
          "assistant",
          "Here are some areas that continue to move quickly:\n\n1. Better tool use and function calling\n2. Longer context and retrieval\n3. Multi-agent collaboration\n4. More reliable planning and verification\n\nIf you want, I can turn this into a one-page brief or a comparison table.",
          hoursAgo(11)
        )
      ]
    ),
    makeThread(
      "project-roadmap",
      "Project Roadmap",
      daysAgo(4),
      daysAgo(1),
      "history",
      [
        threadMessage("user", "Can you review the project roadmap?", daysAgo(1)),
        threadMessage(
          "assistant",
          "Absolutely. Here is the current roadmap:\n\n- Q1: Planning and research\n- Q2: Core development\n- Q3: Testing and QA\n- Q4: Rollout and optimization\n\nTell me if you want this broken into milestones or risks.",
          daysAgo(1)
        )
      ]
    ),
    makeThread(
      "database-optimization",
      "Database Optimization",
      daysAgo(5),
      daysAgo(2),
      "history",
      [
        threadMessage("user", "Can you review the database optimization plan?", daysAgo(2)),
        threadMessage(
          "assistant",
          "Key items to keep in the plan:\n\n- Index the high-traffic query paths\n- Reduce duplicate writes\n- Add query tracing to the slowest endpoints\n- Revisit cache TTLs after the next release",
          daysAgo(2)
        )
      ]
    ),
    makeThread(
      "meeting-notes",
      "Meeting Notes",
      daysAgo(7),
      daysAgo(3),
      "history",
      [
        threadMessage("user", "Summarize the meeting notes", daysAgo(3)),
        threadMessage(
          "assistant",
          "Summary:\n\n- Alignment on the release timeline\n- Open question around external sign-in\n- Follow-up needed on design review\n- One owner assigned per action item",
          daysAgo(3)
        )
      ]
    )
  ];
}

function buildSeedIntegrations(): IntegrationItem[] {
  return [
    {
      id: "google-workspace",
      label: "Google Workspace",
      subtitle: "Gmail, Calendar, Drive, Contacts",
      status: "connected",
      detail: "Connected"
    },
    {
      id: "slack",
      label: "Slack",
      subtitle: "Workspace messages",
      status: "connected",
      detail: "Connected"
    },
    {
      id: "notion",
      label: "Notion",
      subtitle: "Docs and databases",
      status: "attention",
      detail: "Needs attention"
    },
    {
      id: "github",
      label: "GitHub",
      subtitle: "Repos, issues, pull requests",
      status: "connected",
      detail: "Connected"
    },
    {
      id: "microsoft-outlook",
      label: "Microsoft Outlook",
      subtitle: "Mail, calendar, contacts",
      status: "not-connected",
      detail: "Not connected"
    }
  ];
}

function buildSeedState(): GuildState {
  return {
    threads: buildSeedThreads(),
    appearance: {
      theme: "light",
      textSize: "medium",
      reduceMotion: false
    },
    integrations: {
      list: buildSeedIntegrations(),
      googleWorkspace: {
        accountName: "guild.workspace@company.com",
        authStatus: "connected",
        lastRefreshedAt: minutesAgo(12),
        connectedServices: [
          { label: "Gmail", status: "connected" },
          { label: "Calendar", status: "connected" },
          { label: "Drive", status: "connected" },
          { label: "Contacts", status: "connected" }
        ]
      }
    }
  };
}

export function loadGuildState(): GuildState {
  if (typeof window === "undefined") return buildSeedState();

  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (!raw) return buildSeedState();

  try {
    const parsed = JSON.parse(raw) as Partial<GuildState>;
    const seed = buildSeedState();

    return {
      threads: Array.isArray(parsed.threads) && parsed.threads.length ? (parsed.threads as ChatThread[]) : seed.threads,
      appearance: {
        ...seed.appearance,
        ...(parsed.appearance || {})
      } as AppearanceSettings,
      integrations: {
        list: Array.isArray(parsed.integrations?.list) && parsed.integrations?.list.length
          ? (parsed.integrations.list as IntegrationItem[])
          : seed.integrations.list,
        googleWorkspace: {
          ...seed.integrations.googleWorkspace,
          ...(parsed.integrations?.googleWorkspace || {})
        }
      }
    };
  } catch {
    return buildSeedState();
  }
}

export function saveGuildState(state: GuildState) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

export function getThread(state: GuildState, threadId: string) {
  return state.threads.find((thread) => thread.id === threadId) || null;
}

export function createThreadFromPrompt(prompt: string): ChatThread {
  const title = prompt
    .replace(/[?!.]+$/g, "")
    .split(/\s+/)
    .slice(0, 4)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1).toLowerCase())
    .join(" ");

  const createdAt = nowIso();
  return {
    id: uid("thread"),
    title: title || "New Chat",
    createdAt,
    updatedAt: createdAt,
    source: "draft",
    messages: []
  };
}

export function createBlankThread() {
  return {
    id: uid("thread"),
    title: "New Chat",
    createdAt: nowIso(),
    updatedAt: nowIso(),
    source: "draft" as const,
    messages: [] as ChatMessage[]
  };
}

function createReply(prompt: string) {
  const lower = prompt.toLowerCase();
  if (lower.includes("server") || lower.includes("status")) {
    return "All systems are operational.\n\n- Web servers: 4 healthy\n- API servers: 3 healthy\n- Database: 1 healthy\n- Cache: 2 healthy\n\nEverything is running normally.\n\nLast updated: just now";
  }
  if (lower.includes("email") || lower.includes("inbox")) {
    return "Here is a summary of your unread emails from today:\n\n- Project update from Alex\n- Meeting request with Sarah\n- Invoice approval needed\n- New design assets shared by the team";
  }
  if (lower.includes("agent") || lower.includes("research")) {
    return "Recent advances worth tracking:\n\n1. Better tool use and function calling\n2. Longer context plus retrieval\n3. Multi-agent workflows\n4. More reliable planning and verification\n\nIf you want, I can turn this into a brief or a comparison table.";
  }

  return "I can help with that.\n\nIf you'd like, I can turn this into a summary, a plan, or a concise next-step checklist.";
}

export function submitPrompt(state: GuildState, threadId: string, prompt: string): GuildState {
  const thread = getThread(state, threadId);
  if (!thread) return state;

  const createdAt = nowIso();
  const userMessage = threadMessage("user", prompt, createdAt);
  const assistantMessage = threadMessage("assistant", createReply(prompt), nowIso());

  return {
    ...state,
    threads: state.threads.map((item) =>
      item.id === threadId
        ? {
            ...item,
            updatedAt: assistantMessage.createdAt,
            title: item.source === "draft" ? createThreadFromPrompt(prompt).title : item.title,
            messages: [...item.messages, userMessage, assistantMessage]
          }
        : item
    )
  };
}

export function addThread(state: GuildState, thread: ChatThread) {
  return {
    ...state,
    threads: [thread, ...state.threads]
  };
}

export function updateThread(state: GuildState, threadId: string, updater: (thread: ChatThread) => ChatThread) {
  return {
    ...state,
    threads: state.threads.map((thread) => (thread.id === threadId ? updater(thread) : thread))
  };
}

export function clearHistory(state: GuildState) {
  const seed = buildSeedState();
  const keepIds = new Set(["check-server-status", "summarize-emails", "research-ai-agents"]);
  return {
    ...state,
    threads: seed.threads.filter((thread) => keepIds.has(thread.id))
  };
}

export function resetToSeededState() {
  return buildSeedState();
}

export function refreshGoogleWorkspace(state: GuildState): GuildState {
  return {
    ...state,
    integrations: {
      ...state.integrations,
      googleWorkspace: {
        ...state.integrations.googleWorkspace,
        authStatus: "connected" as const,
        lastRefreshedAt: nowIso(),
        connectedServices: state.integrations.googleWorkspace.connectedServices.map((service) => ({
          ...service,
          status: "connected" as const
        }))
      }
    }
  };
}

export function reconnectGoogleWorkspace(state: GuildState): GuildState {
  return refreshGoogleWorkspace({
    ...state,
    integrations: {
      ...state.integrations,
      googleWorkspace: {
        ...state.integrations.googleWorkspace,
        authStatus: "connected" as const
      }
    }
  });
}

export function disconnectGoogleWorkspace(state: GuildState): GuildState {
  return {
    ...state,
    integrations: {
      ...state.integrations,
      googleWorkspace: {
        ...state.integrations.googleWorkspace,
        authStatus: "not-connected" as const,
        lastRefreshedAt: nowIso(),
        connectedServices: state.integrations.googleWorkspace.connectedServices.map((service) => ({
          ...service,
          status: service.label === "Gmail" ? ("attention" as const) : ("not-connected" as const)
        }))
      }
    }
  };
}

export function setAppearanceTheme(state: GuildState, theme: AppearanceSettings["theme"]) {
  return {
    ...state,
    appearance: {
      ...state.appearance,
      theme
    }
  };
}

export function setTextSize(state: GuildState, textSize: AppearanceSettings["textSize"]) {
  return {
    ...state,
    appearance: {
      ...state.appearance,
      textSize
    }
  };
}

export function setReduceMotion(state: GuildState, reduceMotion: boolean) {
  return {
    ...state,
    appearance: {
      ...state.appearance,
      reduceMotion
    }
  };
}

export function formatRelative(iso: string) {
  const value = new Date(iso).getTime();
  const diff = value - Date.now();
  const abs = Math.abs(diff);
  const rtf = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  if (abs >= 24 * 60 * 60_000) return rtf.format(Math.round(diff / (24 * 60 * 60_000)), "day");
  if (abs >= 60 * 60_000) return rtf.format(Math.round(diff / (60 * 60_000)), "hour");
  if (abs >= 60_000) return rtf.format(Math.round(diff / 60_000), "minute");
  return rtf.format(Math.round(diff / 1000), "second");
}

export function formatDateLabel(iso: string) {
  return new Intl.DateTimeFormat("en", {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit"
  }).format(new Date(iso));
}

export function downloadStateSnapshot(state: GuildState) {
  if (typeof document === "undefined") return;
  const blob = new Blob([JSON.stringify(state, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `guild-state-${new Date().toISOString().slice(0, 10)}.json`;
  link.click();
  URL.revokeObjectURL(url);
}
