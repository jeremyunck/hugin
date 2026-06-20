import { useCallback, useEffect, useRef, useState, type ChangeEvent, type ReactNode, type RefObject } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  ArrowLeft,
  Bug,
  Box,
  ChevronDown,
  ChevronRight,
  FileText,
  Folder,
  FolderOpen,
  GitBranch,
  Github,
  History,
  Image as ImageIcon,
  Lock,
  Menu,
  MessageCirclePlus,
  MessageSquare,
  Network,
  Plus,
  Puzzle,
  RefreshCw,
  Search,
  Send,
  SlidersHorizontal,
  Settings2,
  Trash2,
  User,
  X
} from "lucide-react";

import {
  connectGitHub,
  createSandbox,
  createGitHubSandbox,
  createThread,
  deleteSandbox,
  deleteThreadHistory,
  disconnectGitHub,
  disconnectGoogle,
  cancelAgentRun,
  fetchBugReports,
  fetchAgentRuns,
  fetchChatSessionEvents,
  fetchGitHubBranches,
  fetchGitHubRepositories,
  fetchGitHubStatus,
  fetchModels,
  fetchCurrentUser,
  fetchIntegrations,
  fetchSandboxFiles,
  formatTimestamp,
  getThreadTitle,
  loadAppState,
  loadAuthSession,
  login as loginRequest,
  reconnectGoogle,
  removeThread,
  reportBug,
  saveEnabledModels,
  saveAppState,
  saveAuthSession,
  sendChatMessage,
  openChatEventStream,
  type ChatEvent
} from "./services/guildService";
import type {
  AppState,
  AuthSession,
  AgentRun,
  ChatActivity,
  ChatAttachment,
  ChatEntry,
  ChatThread,
  FileNode,
  BugReportSummary,
  GitHubBranch,
  GitHubRepository,
  GitHubStatus,
  Integration,
  ModelOption
} from "./lib/types";

const LOGO = "/hugin-bird.jpg";

const COLORS = {
  ink: "#1C1F23",
  muted: "#8B9099",
  faint: "#ADB2BA",
  micro: "#BFC3CA",
  border: "#EAEBED",
  badge: "#F1F2F4",
  hover: "#F4F4F6",
  bubble: "#EFEFF1",
  green: "#20A65A",
  danger: "#E5484D"
};

const CHIPS = [
  ["Summarize a document", "Summarize a document for me."],
  ["Analyze data", "Analyze this dataset and show key trends."],
  ["Write code", "Write a Python script to clean a CSV file."],
  ["Brainstorm ideas", "Brainstorm ideas for a product launch."],
  ["Show me tips", "Show me tips for getting the most out of Hugin."]
] as const;

type Screen = "login" | "chat" | "purechat" | "history" | "integrations" | "settings" | "github-repo" | "agent-threads";
const WORKSPACE_ACTION_RE =
  /\b(debug|fix|edit|change|update|inspect|investigate|search|grep|find|open|read|write|modify|patch|refactor|run|build|test|render)\b/i;
const WORKSPACE_TARGET_RE =
  /\b(code|repo|repository|file|files|folder|directory|project|frontend|backend|component|markdown|ui|function|class|css|html|typescript|javascript|java|python|bash|shell|command)\b/i;
const WORKSPACE_PATH_RE = /(^|\s)(\.\/|\/|~\/)[^\s]+/;

function nowIso() {
  return new Date().toISOString();
}

function sanitizeAttachmentForReport(attachment: ChatAttachment) {
  return {
    name: attachment.name,
    mimeType: attachment.mimeType,
    size: attachment.size
  };
}

function serializeThreadForBugReport(thread: ChatThread) {
  return {
    ...thread,
    entries: thread.entries.map((entry) =>
      entry.type !== "user" || !entry.attachments?.length
        ? entry
        : {
            ...entry,
            attachments: entry.attachments.map(sanitizeAttachmentForReport)
          }
    )
  };
}

function resolvePreferredGitHubBranch(
  branches: GitHubBranch[],
  defaultBranch: string | null | undefined,
  currentBranch?: string
) {
  if (currentBranch && branches.some((branch) => branch.name === currentBranch)) {
    return currentBranch;
  }
  if (defaultBranch) {
    const matchingDefault = branches.find((branch) => branch.name === defaultBranch);
    if (matchingDefault) return matchingDefault.name;
  }
  return branches[0]?.name ?? "";
}

function buildGitHubBugReportPrompt(report: BugReportSummary) {
  return [
    `A bug report has been added to this repository checkout at \`${report.relativePath}\`.`,
    "Before making changes, read `docs/skills/hugin-bug-reports/SKILL.md` and use that skill's workflow to inspect the bug report.",
    "Then diagnose the failure, add or update a regression test that covers it, and implement the fix."
  ].join(" ");
}

function promptNeedsWorkspace(prompt: string) {
  return (WORKSPACE_ACTION_RE.test(prompt) && WORKSPACE_TARGET_RE.test(prompt))
    || WORKSPACE_PATH_RE.test(prompt)
    || prompt.includes("```");
}

function readLaunchScreen() {
  if (typeof window === "undefined") return null;
  const params = new URLSearchParams(window.location.search);
  return params.get("screen");
}

const ACTIVE_THREAD_STORAGE_KEY = "hugin-active-thread-v1";

function readActiveThreadRestore() {
  if (typeof window === "undefined") return null;
  try {
    const raw = window.localStorage.getItem(ACTIVE_THREAD_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as { threadId?: string; screen?: Screen };
    if (!parsed.threadId || !parsed.screen) return null;
    return parsed;
  } catch {
    return null;
  }
}

function saveActiveThreadRestore(threadId: string, screen: Screen) {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.setItem(ACTIVE_THREAD_STORAGE_KEY, JSON.stringify({ threadId, screen }));
  } catch {
    // Ignore storage write failures so reconnect support never blocks the active session.
  }
}

function clearActiveThreadRestore() {
  if (typeof window === "undefined") return;
  try {
    window.localStorage.removeItem(ACTIVE_THREAD_STORAGE_KEY);
  } catch {
    // Ignore storage cleanup failures; the next successful write will overwrite stale data.
  }
}

function clearLaunchScreen() {
  if (typeof window === "undefined") return;
  const url = new URL(window.location.href);
  url.searchParams.delete("screen");
  url.searchParams.delete("github");
  url.searchParams.delete("installation_id");
  url.searchParams.delete("setup_action");
  window.history.replaceState({}, "", url.toString());
}

function delay(ms: number) {
  return new Promise<void>((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

function formatBytes(size?: number) {
  if (size == null) return "";
  if (size < 1024) return `${size} b`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} kb`;
  return `${(size / (1024 * 1024)).toFixed(1)} mb`;
}

function messageCount(thread: ChatThread) {
  return thread.entries.filter((entry) => entry.type !== "tool").length;
}

function formatPrice(value?: string | null) {
  if (!value) return "N/A";
  const amount = Number(value);
  if (Number.isNaN(amount)) return value;
  if (amount === 0) return "$0.00";
  if (amount >= 1) return `$${amount.toFixed(2)}`;
  if (amount >= 0.01) return `$${amount.toFixed(3)}`;
  return `$${amount.toFixed(4)}`;
}

function formatContext(value?: number | null) {
  if (!value) return null;
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(value % 1_000_000 === 0 ? 0 : 1)}M ctx`;
  if (value >= 1_000) return `${Math.round(value / 1_000)}K ctx`;
  return `${value} ctx`;
}

function labelReasoning(value: string) {
  return value === "none" ? "Off" : value.charAt(0).toUpperCase() + value.slice(1);
}

function defaultReasoningFor(model?: ModelOption) {
  if (!model || !model.reasoningOptions.length) return undefined;
  return model.reasoningOptions.includes("medium") ? "medium" : model.reasoningOptions[0];
}

const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

function activityLabel(event: ChatEvent) {
  const metadata = event.metadata ?? {};
  const name = typeof metadata.name === "string" ? metadata.name : event.type;
  switch (event.type) {
    case "run_started":
      return "Run started";
    case "run_completed":
      return "Run completed";
    case "run_error":
      return "Run failed";
    case "tool_call_started":
      return `Tool started: ${name}`;
    case "tool_call_completed":
      return `Tool completed: ${name}`;
    case "tool_call_error":
      return `Tool failed: ${name}`;
    default:
      return event.type.replaceAll("_", " ");
  }
}

function activityDetail(event: ChatEvent) {
  const metadata = event.metadata ?? {};
  if (typeof metadata.result === "string" && metadata.result) return metadata.result;
  if (typeof metadata.args === "string" && metadata.args) return metadata.args;
  if (typeof metadata.message === "string" && metadata.message) return metadata.message;
  return event.content ?? undefined;
}

function activityStatus(event: ChatEvent): ChatActivity["status"] {
  if (event.type.endsWith("_error") || event.type === "run_error") return "error";
  if (event.type.endsWith("_completed") || event.type === "run_completed") return "completed";
  if (event.type.endsWith("_started") || event.type === "run_started") return "running";
  return "info";
}

function toolCallId(event: ChatEvent) {
  const raw = event.metadata?.callId;
  return typeof raw === "string" && raw ? raw : undefined;
}

function findToolCompletionIndex(entries: ChatEntry[], name: string, callId?: string) {
  if (callId) {
    return entries.findIndex((entry) => entry.type === "tool" && entry.tool.callId === callId);
  }
  // Legacy events may not have callIds. Match the most recent unfinished tool with the same
  // name so reconnect replay does not accidentally bind to an older completed entry.
  for (let index = entries.length - 1; index >= 0; index -= 1) {
    const entry = entries[index];
    if (entry?.type === "tool" && entry.tool.name === name && !entry.tool.finishedAt) {
      return index;
    }
  }
  return -1;
}

export function reduceChatEvent(thread: ChatThread, event: ChatEvent): ChatThread {
  if ((thread.lastSeq ?? 0) >= event.seq) {
    return thread;
  }

  const entries = thread.entries.slice();
  const activities = (thread.activities ?? []).slice();
  const messageId = event.messageId ?? undefined;
  const index = messageId ? entries.findIndex((entry) => entry.id === messageId) : -1;
  const attachments = Array.isArray(event.metadata?.attachments)
    ? event.metadata.attachments as ChatAttachment[]
    : undefined;

  switch (event.type) {
    case "user_message_created":
      if (messageId && index === -1) {
        entries.push({
          id: messageId,
          type: "user",
          content: event.content ?? "",
          ...(attachments?.length ? { attachments } : {}),
          createdAt: event.createdAt
        });
      }
      break;
    case "assistant_message_started":
      if (messageId && index === -1) {
        entries.push({
          id: messageId,
          type: "assistant",
          content: "",
          reasoning: "",
          createdAt: event.createdAt
        });
      }
      break;
    case "assistant_reasoning":
      if (messageId) {
        if (index === -1) {
          entries.push({
            id: messageId,
            type: "assistant",
            content: "",
            reasoning: event.content ?? "",
            createdAt: event.createdAt
          });
        } else if (entries[index]?.type === "assistant") {
          const current = entries[index] as Extract<ChatEntry, { type: "assistant" }>;
          entries[index] = { ...current, reasoning: `${current.reasoning}${event.content ?? ""}` };
        }
      }
      break;
    case "assistant_token":
      if (messageId) {
        if (index === -1) {
          entries.push({
            id: messageId,
            type: "assistant",
            content: event.content ?? "",
            reasoning: "",
            createdAt: event.createdAt
          });
        } else if (entries[index]?.type === "assistant") {
          const current = entries[index] as Extract<ChatEntry, { type: "assistant" }>;
          entries[index] = { ...current, content: `${current.content}${event.content ?? ""}` };
        }
      }
      break;
    case "tool_call_started": {
      const name = typeof event.metadata?.name === "string" && event.metadata.name
        ? event.metadata.name
        : "tool";
      const args = typeof event.metadata?.args === "string" ? event.metadata.args : "";
      const callId = toolCallId(event);
      const toolIndex = callId
        ? entries.findIndex((entry) => entry.type === "tool" && entry.tool.callId === callId)
        : -1;
      if (toolIndex === -1) {
        entries.push({
          id: event.id,
          type: "tool",
          tool: {
            id: event.id,
            ...(callId ? { callId } : {}),
            name,
            args,
            result: "",
            startedAt: event.createdAt
          },
          createdAt: event.createdAt
        });
      }
      activities.push({
        id: event.id,
        runId: event.runId ?? undefined,
        type: event.type,
        label: activityLabel(event),
        status: activityStatus(event),
        detail: activityDetail(event),
        createdAt: event.createdAt
      });
      break;
    }
    case "tool_call_completed": {
      const name = typeof event.metadata?.name === "string" && event.metadata.name
        ? event.metadata.name
        : "tool";
      const result = typeof event.metadata?.result === "string" ? event.metadata.result : "";
      const callId = toolCallId(event);
      const toolIndex = findToolCompletionIndex(entries, name, callId);
      if (toolIndex === -1) {
        entries.push({
          id: event.id,
          type: "tool",
          tool: {
            id: event.id,
            ...(callId ? { callId } : {}),
            name,
            args: "",
            result,
            startedAt: event.createdAt,
            finishedAt: event.createdAt
          },
          createdAt: event.createdAt
        });
      } else if (entries[toolIndex]?.type === "tool") {
        const current = entries[toolIndex] as Extract<ChatEntry, { type: "tool" }>;
        entries[toolIndex] = {
          ...current,
          tool: {
            ...current.tool,
            result,
            finishedAt: event.createdAt
          }
        };
      }
      activities.push({
        id: event.id,
        runId: event.runId ?? undefined,
        type: event.type,
        label: activityLabel(event),
        status: activityStatus(event),
        detail: activityDetail(event),
        createdAt: event.createdAt
      });
      break;
    }
    case "assistant_message_completed":
      if (messageId && index !== -1 && entries[index]?.type === "assistant") {
        const current = entries[index] as Extract<ChatEntry, { type: "assistant" }>;
        entries[index] = {
          ...current,
          content: current.content || event.content || "",
          completedAt: event.createdAt
        };
      }
      break;
    case "assistant_message_error":
      if (messageId) {
        if (index === -1) {
          entries.push({
            id: messageId,
            type: "assistant",
            content: event.content ?? "The run failed.",
            reasoning: "",
            createdAt: event.createdAt,
            completedAt: event.createdAt
          });
        } else if (entries[index]?.type === "assistant") {
          const current = entries[index] as Extract<ChatEntry, { type: "assistant" }>;
          entries[index] = {
            ...current,
            content: current.content || event.content || "The run failed.",
            completedAt: event.createdAt
          };
        }
      }
      break;
    default:
      activities.push({
        id: event.id,
        runId: event.runId ?? undefined,
        type: event.type,
        label: activityLabel(event),
        status: activityStatus(event),
        detail: activityDetail(event),
        createdAt: event.createdAt
      });
      break;
  }

  return {
    ...thread,
    entries,
    activities,
    lastSeq: event.seq,
    updatedAt: event.createdAt
  };
}

function screenForThread(thread: ChatThread): Screen {
  return thread.kind === "chat" ? "purechat" : "chat";
}

function mostRecentThread(threads: ChatThread[]): ChatThread | null {
  if (!threads.length) return null;
  return threads.reduce((latest, candidate) =>
    Date.parse(candidate.updatedAt) > Date.parse(latest.updatedAt) ? candidate : latest
  );
}

export function restorePreferredThread(threads: ChatThread[]) {
  const active = readActiveThreadRestore();
  if (active?.threadId) {
    const match = threads.find((thread) => thread.id === active.threadId);
    if (match) {
      return {
        thread: match,
        screen: active.screen
      };
    }
  }
  const fallback = mostRecentThread(threads);
  if (!fallback) return null;
  return {
    thread: fallback,
    screen: screenForThread(fallback)
  };
}

function AppHeader({
  onMenu,
  reportAction
}: {
  onMenu: () => void;
  reportAction?: {
    disabled?: boolean;
    busy?: boolean;
    onClick: () => void;
  };
}) {
  return (
    <div className="app-header">
      <div className="brand">
        <img src={LOGO} alt="Hugin" className="brand-logo" />
        <span className="brand-text">HUGIN</span>
      </div>
      <div className="header-actions">
        {reportAction ? (
          <button
            type="button"
            className="header-action-button"
            onClick={reportAction.onClick}
            disabled={reportAction.disabled || reportAction.busy}
            aria-label="Report bug"
          >
            <Bug size={14} strokeWidth={2} />
            <span>{reportAction.busy ? "Saving…" : "Report bug"}</span>
          </button>
        ) : null}
        <button type="button" className="icon-button" onClick={onMenu} aria-label="Open menu">
          <Menu size={22} strokeWidth={2} />
        </button>
      </div>
    </div>
  );
}

function TreeRow({
  depth = 0,
  onClick,
  children
}: {
  depth?: number;
  onClick?: () => void;
  children: ReactNode;
}) {
  return (
    <div
      className={`tree-row ${onClick ? "tree-row-clickable" : ""}`}
      style={{ paddingLeft: depth * 16 }}
      onClick={onClick}
    >
      {children}
    </div>
  );
}

function FileNodeRow({ node, depth, defaultOpen }: { node: FileNode; depth: number; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen);

  if (node.type === "dir") {
    return (
      <>
        <TreeRow depth={depth} onClick={() => setOpen((current) => !current)}>
          {open ? <ChevronDown size={13} color={COLORS.faint} /> : <ChevronRight size={13} color={COLORS.faint} />}
          {open ? (
            <FolderOpen size={14} strokeWidth={2} color={COLORS.ink} />
          ) : (
            <Folder size={14} strokeWidth={2} color={COLORS.ink} />
          )}
          <span>{node.name}</span>
        </TreeRow>
        {open ? node.children?.map((child) => (
          <FileNodeRow key={child.path} node={child} depth={depth + 1} defaultOpen={defaultOpen} />
        )) : null}
      </>
    );
  }

  return (
    <TreeRow depth={depth}>
      <FileText size={13.5} strokeWidth={2} color={COLORS.muted} />
      <span className="mono">{node.name}</span>
      <span className="tree-size mono">{formatBytes(node.size)}</span>
    </TreeRow>
  );
}

export function FileTree(props: {
  sessionId: string;
  files: FileNode[];
  wsOpen: boolean;
  onToggleWs: () => void;
  label: string;
  rootName: string;
  badge: string;
  defaultOpenDirectories: boolean;
}) {
  const { sessionId, files, wsOpen, onToggleWs, label, rootName, badge, defaultOpenDirectories } = props;

  return (
    <div className="file-tree">
      <TreeRow>
        <ChevronDown size={13} color={COLORS.faint} />
        <Network size={14} strokeWidth={2} color={COLORS.ink} />
        <span className="mono">{label || `~/sandbox/${sessionId.slice(0, 8)}`}</span>
        <span className="tree-badge">{badge}</span>
      </TreeRow>

      <TreeRow depth={1} onClick={onToggleWs}>
        {wsOpen ? <ChevronDown size={13} color={COLORS.faint} /> : <ChevronRight size={13} color={COLORS.faint} />}
        {wsOpen ? (
          <FolderOpen size={14} strokeWidth={2} color={COLORS.ink} />
        ) : (
          <Folder size={14} strokeWidth={2} color={COLORS.ink} />
        )}
        <span>{rootName}</span>
      </TreeRow>

      {wsOpen ? (
        files.length ? (
          files.map((node) => <FileNodeRow key={node.path} node={node} depth={2} defaultOpen={defaultOpenDirectories} />)
        ) : (
          <TreeRow depth={2}>
            <span className="mono" style={{ color: COLORS.faint }}>
              (empty)
            </span>
          </TreeRow>
        )
      ) : null}
    </div>
  );
}

function RepoSetupScreen(props: {
  busy: boolean;
  loadingRepos: boolean;
  loadingBranches: boolean;
  loadingBugReports: boolean;
  repositories: GitHubRepository[];
  branches: GitHubBranch[];
  bugReports: BugReportSummary[];
  selectedRepo: string;
  selectedBranch: string;
  selectedBugReportId: string;
  error: string | null;
  onBack: () => void;
  onRepoChange: (value: string) => void;
  onBranchChange: (value: string) => void;
  onBugReportChange: (value: string) => void;
  onConfirm: () => void;
}) {
  const {
    busy,
    loadingRepos,
    loadingBranches,
    loadingBugReports,
    repositories,
    branches,
    bugReports,
    selectedRepo,
    selectedBranch,
    selectedBugReportId,
    error,
    onBack,
    onRepoChange,
    onBranchChange,
    onBugReportChange,
    onConfirm
  } = props;
  const selectedRepoMeta = repositories.find((repo) => repo.fullName === selectedRepo);
  const ready = Boolean(selectedRepo && selectedBranch) && !busy && !loadingRepos && !loadingBranches;

  return (
    <>
      <div className="back-row">
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">GitHub repo chat</h1>
        <p className="integration-subtitle">
          Pick a repository and branch, then Hugin will open a fresh sandbox with a clean pull of that branch.
        </p>
      </div>

      <div className="repo-setup-card">
        <label className="composer-select repo-setup-select">
          <span>Repository</span>
          <select value={selectedRepo} onChange={(event) => onRepoChange(event.target.value)} disabled={busy || loadingRepos}>
            <option value="">{loadingRepos ? "Loading repositories…" : "Select a repository"}</option>
            {repositories.map((repo) => (
              <option key={repo.fullName} value={repo.fullName}>
                {repo.fullName}
              </option>
            ))}
          </select>
        </label>

        <label className="composer-select repo-setup-select">
          <span>Branch</span>
          <select
            value={selectedBranch}
            onChange={(event) => onBranchChange(event.target.value)}
            disabled={busy || !selectedRepo || loadingBranches}
          >
            <option value="">
              {!selectedRepo ? "Select a repository first" : loadingBranches ? "Loading branches…" : "Select a branch"}
            </option>
            {branches.map((branch) => (
              <option key={branch.name} value={branch.name}>
                {branch.name}
              </option>
            ))}
          </select>
        </label>

        <label className="composer-select repo-setup-select">
          <span>Bug report</span>
          <select
            value={selectedBugReportId}
            onChange={(event) => onBugReportChange(event.target.value)}
            disabled={busy || loadingBugReports}
          >
            <option value="">
              {loadingBugReports ? "Loading bug reports…" : "None"}
            </option>
            {bugReports.map((report) => (
              <option key={report.id} value={report.id}>
                {report.title}
              </option>
            ))}
          </select>
        </label>

        {selectedRepoMeta ? (
          <div className="repo-summary">
            <div className="repo-summary-title">
              <Github size={16} strokeWidth={2} />
              <span>{selectedRepoMeta.fullName}</span>
            </div>
            <div className="repo-summary-meta">
              <span>{selectedRepoMeta.privateRepo ? "Private" : "Public"}</span>
              <span>Default {selectedRepoMeta.defaultBranch || "unknown"}</span>
            </div>
            {selectedRepoMeta.description ? <p>{selectedRepoMeta.description}</p> : null}
          </div>
        ) : null}

        {error ? <p className="login-error">{error}</p> : null}

        <button type="button" className="primary-button repo-confirm-button" onClick={onConfirm} disabled={!ready}>
          {busy ? "Creating sandbox…" : "Open repo sandbox"}
        </button>
      </div>
    </>
  );
}

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

function TypingDots() {
  return (
    <span className="typing-dots">
      <span className="dot" />
      <span className="dot" />
      <span className="dot" />
    </span>
  );
}

function normalizeAssistantMarkdown(content: string) {
  return content.replace(/<br\s*\/?>/gi, "\n");
}

export function Messages({
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

        if (entry.type !== "assistant") {
          if (entry.type === "tool") {
            return (
              <details key={entry.id} className="message-row tool-event fade-in" open>
                <summary className="assistant-event">
                  <div className="assistant-response">
                    <span className="bullet-mark">•</span>{" "}
                    <span className="mono">{entry.tool.name}</span>
                  </div>
                </summary>
                <div className="tool-event-body">
                  <div className="tool-event-section">
                    <span className="tool-event-label">Arguments</span>
                    <pre>{entry.tool.args || "(none)"}</pre>
                  </div>
                  <div className="tool-event-section">
                    <span className="tool-event-label">Result</span>
                    <pre>{entry.tool.result || "(running)"}</pre>
                  </div>
                </div>
              </details>
            );
          }
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

function ActivityPanel({ activities }: { activities: ChatActivity[] }) {
  if (!activities.length) return null;
  return (
    <div className="activity-panel">
      <div className="activity-header">Activity</div>
      <div className="activity-list">
        {activities.map((activity) => (
          <div key={activity.id} className="activity-item">
            <div className="activity-item-head">
              <span className={`activity-status activity-status-${activity.status}`} />
              <span>{activity.label}</span>
            </div>
            {activity.detail ? <pre className="activity-detail">{activity.detail}</pre> : null}
          </div>
        ))}
      </div>
    </div>
  );
}

function InputBar(props: {
  value: string;
  disabled: boolean;
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
}) {
  const {
    value,
    disabled,
    attachment,
    models,
    selectedModelId,
    selectedReasoning,
    onChange,
    onModelChange,
    onReasoningChange,
    onPickImage,
    onClearImage,
    onSend
  } = props;
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
        <button type="button" onClick={onPickImage} disabled={disabled} aria-label="Add image">
          <ImageIcon size={18} strokeWidth={2} color={COLORS.ink} />
        </button>
        <input
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !disabled && (value.trim() || attachment)) {
              event.preventDefault();
              onSend();
            }
          }}
          enterKeyHint="send"
          autoComplete="off"
          autoCorrect="on"
          autoCapitalize="sentences"
          spellCheck
          placeholder={attachment ? "Ask about this image..." : "Message Hugin…"}
        />
        <button
          type="button"
          onClick={onSend}
          disabled={disabled || (!value.trim() && !attachment)}
          aria-label="Send message"
        >
          <Send size={19} strokeWidth={2} color={COLORS.ink} />
        </button>
      </div>
      <div className="composer-controls">
        <label className="composer-select">
          <span>Model</span>
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
        </label>
        <label className="composer-select">
          <span>Reasoning</span>
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
        </label>
      </div>
      <p className="input-note">Hugin can make mistakes. Please verify important information.</p>
    </div>
  );
}

function MenuOverlay(props: {
  username: string;
  roles: string[];
  githubConnected: boolean;
  onClose: () => void;
  onSandbox: () => void;
  onGitHubRepo: () => void;
  onChat: () => void;
  onHistory: () => void;
  onAgentThreads: () => void;
  onIntegrations: () => void;
  onSettings: () => void;
}) {
  const { username, roles, githubConnected, onClose, onSandbox, onGitHubRepo, onChat, onHistory, onAgentThreads, onIntegrations, onSettings } = props;
  const initials = username.slice(0, 2).toUpperCase();

  return (
    <div className="menu-overlay">
      <button type="button" className="menu-backdrop backdrop-fade" onClick={onClose} aria-label="Close menu" />
      <div className="menu-panel panel-in">
        <div className="menu-close">
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close menu">
            <X size={22} strokeWidth={2} />
          </button>
        </div>

        <nav className="menu-nav">
          <button type="button" className="menu-item" onClick={onChat}>
            <MessageCirclePlus size={18} strokeWidth={2} color={COLORS.ink} />
            <span>New chat</span>
          </button>
          <button type="button" className="menu-item" onClick={onSandbox}>
            <Box size={18} strokeWidth={2} color={COLORS.ink} />
            <span>New sandbox</span>
          </button>
          {githubConnected ? (
            <button type="button" className="menu-item" onClick={onGitHubRepo}>
              <Github size={18} strokeWidth={2} color={COLORS.ink} />
              <span>GitHub repo chat</span>
            </button>
          ) : null}
          <button type="button" className="menu-item" onClick={onHistory}>
            <History size={18} strokeWidth={2} color={COLORS.ink} />
            <span>History</span>
          </button>
          <button type="button" className="menu-item" onClick={onAgentThreads}>
            <Network size={18} strokeWidth={2} color={COLORS.ink} />
            <span>Agent threads</span>
          </button>
          <button type="button" className="menu-item" onClick={onIntegrations}>
            <Puzzle size={18} strokeWidth={2} color={COLORS.ink} />
            <span>Integrations</span>
          </button>
          <button type="button" className="menu-item" onClick={onSettings}>
            <Settings2 size={18} strokeWidth={2} color={COLORS.ink} />
            <span>Model settings</span>
          </button>
        </nav>

        <div className="menu-profile">
          <div className="profile-avatar">{initials}</div>
          <div className="profile-copy">
            <div className="profile-name">{username}</div>
            <div className="profile-email">{roles.join(", ") || "member"}</div>
          </div>
          <ChevronRight size={18} color={COLORS.faint} />
        </div>
      </div>
    </div>
  );
}

function AgentThreadsScreen(props: {
  runs: AgentRun[];
  threads: ChatThread[];
  busyRunId: string | null;
  loading: boolean;
  onBack: () => void;
  onCancel: (id: string) => void;
}) {
  const { runs, threads, busyRunId, loading, onBack, onCancel } = props;

  const labelForRun = (run: AgentRun) => {
    const match = run.sessionId ? threads.find((thread) => thread.id === run.sessionId) : null;
    if (match) return match.title;
    if (run.prompt) return run.prompt;
    return run.sessionId || "Active run";
  };

  return (
    <>
      <div className="back-row">
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">Agent threads</h1>
        <p className="integration-subtitle">
          Running agent requests continue on the server after a client disconnect. Cancel them here when needed.
        </p>
      </div>

      <div className="integrations-list">
        <div className="history-group-label">ACTIVE RUNS</div>
        {loading && runs.length === 0 ? (
          <p className="history-empty">Loading active runs…</p>
        ) : runs.length === 0 ? (
          <p className="history-empty">No active agent threads.</p>
        ) : (
          runs.map((run) => (
            <div key={run.id} className="integration-card">
              <div className="integration-copy">
                <div className="integration-name-row">
                  <span className="integration-name">{labelForRun(run)}</span>
                  {run.disconnected ? <span className="integration-badge">DISCONNECTED</span> : null}
                  {run.cancellationRequested ? <span className="integration-badge">CANCELLING</span> : null}
                </div>
                <div className="integration-meta">{run.model || "Default model"}</div>
                <div className="model-description">
                  Started {formatTimestamp(run.startedAt)}
                  {run.sessionId ? ` • ${run.sessionId}` : ""}
                </div>
              </div>
              <button
                type="button"
                className="secondary-button"
                disabled={run.cancellationRequested || busyRunId === run.id}
                onClick={() => onCancel(run.id)}
              >
                {busyRunId === run.id || run.cancellationRequested ? "Cancelling…" : "Cancel"}
              </button>
            </div>
          ))
        )}
      </div>
    </>
  );
}

export function HistoryScreen(props: {
  threads: ChatThread[];
  onMenu: () => void;
  onOpen: (thread: ChatThread) => void;
  onDelete: (thread: ChatThread) => void;
  onNew: () => void;
  deletingId: string | null;
  query: string;
  onQuery: (value: string) => void;
}) {
  const { threads, onMenu, onOpen, onNew, onDelete, deletingId, query, onQuery } = props;
  const lower = query.trim().toLowerCase();
  const match = (thread: ChatThread) => !lower || thread.title.toLowerCase().includes(lower);
  const now = Date.now();
  const isToday = (iso: string) => now - new Date(iso).getTime() < 24 * 60 * 60 * 1000;

  const matched = threads.filter(match);
  const groups: Array<[string, ChatThread[]]> = [
    ["TODAY", matched.filter((thread) => isToday(thread.updatedAt))],
    ["EARLIER", matched.filter((thread) => !isToday(thread.updatedAt))]
  ];
  const anyResults = matched.length > 0;

  return (
    <>
      <AppHeader onMenu={onMenu} />
      <h1 className="screen-title">History</h1>

      <div className="screen-pad">
        <div className="search-bar">
          <Search size={17} strokeWidth={2} color={COLORS.faint} />
          <input
            value={query}
            onChange={(event) => onQuery(event.target.value)}
            placeholder="Search history…"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
          />
          <SlidersHorizontal size={16} strokeWidth={2} color={COLORS.faint} />
        </div>
      </div>

      <div className="history-list">
        {groups.map(([label, items], groupIndex) => {
          if (!items.length) return null;
          return (
            <div key={label} className={groupIndex > 0 ? "history-group history-group-spaced" : "history-group"}>
              <div className="history-group-label">{label}</div>
              <div className="history-cards">
                {items.map((thread) => (
                  <div key={thread.id} className="history-card-row">
                    <button type="button" className="history-card" onClick={() => onOpen(thread)}>
                      <div className="history-card-icon">
                        {thread.kind === "sandbox" ? (
                          <Box size={17} strokeWidth={2} color={COLORS.ink} />
                        ) : thread.kind === "github" ? (
                          <GitBranch size={17} strokeWidth={2} color={COLORS.ink} />
                        ) : (
                          <MessageSquare size={17} strokeWidth={2} color={COLORS.ink} />
                        )}
                      </div>
                      <div className="history-card-copy">
                        <div className="history-card-title">{thread.title}</div>
                        <div className="history-card-meta">
                          {formatTimestamp(thread.updatedAt)} · {messageCount(thread)} messages
                        </div>
                      </div>
                      <ChevronRight size={18} color={COLORS.faint} />
                    </button>
                    <button
                      type="button"
                      className="history-card-delete"
                      aria-label={`Delete ${thread.title}`}
                      title="Delete conversation"
                      disabled={deletingId === thread.id}
                      onClick={() => onDelete(thread)}
                    >
                      <Trash2 size={17} strokeWidth={2} color={COLORS.danger} />
                    </button>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
        {!anyResults ? (
          <p className="history-empty">
            {threads.length ? `No sessions match “${query.trim()}”.` : "No conversations yet."}
          </p>
        ) : null}
      </div>

      <div className="screen-pad history-footer">
        <button type="button" className="primary-button" onClick={onNew}>
          <Plus size={18} strokeWidth={2.4} /> New chat
        </button>
      </div>
    </>
  );
}

function IntegrationsScreen(props: {
  integrations: Integration[];
  loading: boolean;
  error: string | null;
  busyId: string | null;
  onBack: () => void;
  onToggle: (integration: Integration) => void;
  onReconnect: (integration: Integration) => void;
}) {
  const { integrations, loading, error, busyId, onBack, onToggle, onReconnect } = props;

  return (
    <>
      <div className="back-row">
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">Integrations</h1>
        <p className="integration-subtitle">Manage your connected services. Connected tools are made available to Hugin.</p>
        {loading ? <p className="integration-subtitle">Refreshing integration status…</p> : null}
        {!loading && error ? <p className="login-error">{error}</p> : null}
      </div>

      <div className="integrations-list">
        <div className="history-group-label">SERVICES</div>
        {integrations.length === 0 ? (
          <p className="history-empty">{loading ? "Refreshing integrations…" : "No integrations available."}</p>
        ) : (
          integrations.map((integration) => (
            <div key={integration.id} className="integration-card">
              <Puzzle size={26} strokeWidth={1.7} color={COLORS.ink} />
              <div className="integration-copy">
                <div className="integration-name-row">
                  <span className="integration-name">{integration.name}</span>
                  {integration.connected ? <span className="integration-badge">CONNECTED</span> : null}
                </div>
                <div className="integration-meta">{integration.description}</div>
              </div>
              <div className="integration-action">
                {integration.reconnectable ? (
                  integration.connected ? (
                    <>
                      <button
                        type="button"
                        className="icon-button refresh-button"
                        disabled={busyId === integration.id}
                        onClick={() => onReconnect(integration)}
                        aria-label={`Refresh ${integration.name} connection`}
                        title="Reconnect to refresh permissions"
                      >
                        <RefreshCw size={18} strokeWidth={2} />
                      </button>
                      <button
                        type="button"
                        className="secondary-button"
                        disabled={busyId === integration.id}
                        onClick={() => onToggle(integration)}
                      >
                        {busyId === integration.id ? "…" : "Disconnect"}
                      </button>
                    </>
                  ) : (
                    <button
                      type="button"
                      className="dark-button"
                      disabled={busyId === integration.id}
                      onClick={() => onToggle(integration)}
                    >
                      {busyId === integration.id ? "Connecting…" : "Connect"}
                    </button>
                  )
                ) : (
                  <span className="integration-meta">{integration.connected ? "Active" : "Off"}</span>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </>
  );
}

function SettingsScreen(props: {
  models: ModelOption[];
  saving: boolean;
  onBack: () => void;
  onToggle: (modelId: string) => void;
  onSave: () => void;
}) {
  const { models, saving, onBack, onToggle, onSave } = props;
  const [searchQuery, setSearchQuery] = useState("");
  const enabledCount = models.filter((model) => model.enabled).length;

  const filteredModels = searchQuery.trim()
    ? models.filter(
        (model) =>
          model.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
          model.id.toLowerCase().includes(searchQuery.toLowerCase())
      )
    : models;

  return (
    <>
      <div className="back-row">
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">Model settings</h1>
        <p className="integration-subtitle">
          Choose which OpenRouter models appear in chat. Prices are shown per million input and output tokens.
        </p>
      </div>

      <div className="screen-pad">
        <div className="search-bar">
          <Search size={17} strokeWidth={2} color={COLORS.faint} />
          <input
            value={searchQuery}
            onChange={(event) => setSearchQuery(event.target.value)}
            placeholder="Search models…"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
          />
        </div>
      </div>

      <div className="integrations-list">
        <div className="history-group-label">AVAILABLE MODELS</div>
        {filteredModels.length === 0 ? (
          <p className="history-empty">No models match your search.</p>
        ) : (
          filteredModels.map((model) => (
            <label key={model.id} className={`model-card ${model.enabled ? "model-card-enabled" : ""}`}>
              <div className="model-card-main">
                <div className="model-toggle">
                  <input type="checkbox" checked={model.enabled} onChange={() => onToggle(model.id)} />
                </div>
                <div className="integration-copy">
                  <div className="integration-name-row">
                    <span className="integration-name">{model.name}</span>
                    {model.enabled ? <span className="integration-badge">ENABLED</span> : null}
                  </div>
                  <div className="integration-meta">{model.id}</div>
                  {model.description ? <div className="model-description">{model.description}</div> : null}
                  <div className="model-metrics">
                    <span>Input {formatPrice(model.promptPrice)}/M</span>
                    <span>Output {formatPrice(model.completionPrice)}/M</span>
                    {formatContext(model.contextLength) ? <span>{formatContext(model.contextLength)}</span> : null}
                  </div>
                </div>
              </div>
            </label>
          ))
        )}
      </div>

      <div className="screen-pad history-footer">
        <button type="button" className="primary-button" onClick={onSave} disabled={saving || enabledCount === 0}>
          {saving ? "Saving…" : "Save model settings"}
        </button>
      </div>
    </>
  );
}

function Field(props: {
  icon: typeof User;
  label: string;
  value: string;
  type?: string;
  placeholder: string;
  onChange: (value: string) => void;
  onEnter?: () => void;
}) {
  const { icon: Icon, label, value, type = "text", placeholder, onChange, onEnter } = props;

  return (
    <label className="login-field">
      <span>{label}</span>
      <div className="login-input">
        <Icon size={18} strokeWidth={2} color={COLORS.faint} />
        <input
          type={type}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") onEnter?.();
          }}
          placeholder={placeholder}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
        />
      </div>
    </label>
  );
}

function LoginScreen(props: {
  username: string;
  password: string;
  error: string | null;
  busy: boolean;
  onUser: (value: string) => void;
  onPass: (value: string) => void;
  onSignIn: () => void;
}) {
  const { username, password, error, busy, onUser, onPass, onSignIn } = props;
  const ready = Boolean(username.trim() && password.trim()) && !busy;

  return (
    <div className="login-screen">
      <div className="login-brand">
        <img src={LOGO} alt="Hugin" className="login-logo" />
        <span className="login-wordmark">HUGIN</span>
        <p>Sign in to your workspace</p>
      </div>

      <div className="login-fields">
        <Field
          icon={User}
          label="Username"
          value={username}
          placeholder="Enter your username"
          onChange={onUser}
          onEnter={() => ready && onSignIn()}
        />
        <Field
          icon={Lock}
          label="Password"
          type="password"
          value={password}
          placeholder="Enter your password"
          onChange={onPass}
          onEnter={() => ready && onSignIn()}
        />
      </div>

      {error ? <p className="login-error">{error}</p> : null}

      <button type="button" className="signin-button" disabled={!ready} onClick={() => ready && onSignIn()}>
        {busy ? "Signing in…" : "Sign in"}
      </button>
    </div>
  );
}

export default function App() {
  const [booting, setBooting] = useState(true);
  const [session, setSession] = useState<AuthSession | null>(null);
  const [screen, setScreen] = useState<Screen>("login");
  const [menuOpen, setMenuOpen] = useState(false);

  const [state, setState] = useState<AppState>(() => loadAppState());
  const [thread, setThread] = useState<ChatThread>(() => createThread("chat"));
  const stateRef = useRef(state);
  const threadRef = useRef(thread);

  const [draft, setDraft] = useState("");
  const [draftAttachment, setDraftAttachment] = useState<ChatAttachment | null>(null);
  const [busy, setBusy] = useState(false);
  const [reportingBug, setReportingBug] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [bugReportNotice, setBugReportNotice] = useState<string | null>(null);

  const [files, setFiles] = useState<FileNode[]>([]);
  const [wsOpen, setWsOpen] = useState(true);
  const [githubStatus, setGitHubStatus] = useState<GitHubStatus | null>(null);
  const [repoOptions, setRepoOptions] = useState<GitHubRepository[]>([]);
  const [branchOptions, setBranchOptions] = useState<GitHubBranch[]>([]);
  const [selectedRepo, setSelectedRepo] = useState("");
  const [selectedBranch, setSelectedBranch] = useState("");
  const [loadingRepos, setLoadingRepos] = useState(false);
  const [loadingBranches, setLoadingBranches] = useState(false);
  const [bugReports, setBugReports] = useState<BugReportSummary[]>([]);
  const [selectedBugReportId, setSelectedBugReportId] = useState("");
  const [loadingBugReports, setLoadingBugReports] = useState(false);
  const [pendingAutoPrompt, setPendingAutoPrompt] = useState<string | null>(null);

  const [integrations, setIntegrations] = useState<Integration[]>([]);
  const [integrationsLoading, setIntegrationsLoading] = useState(false);
  const [integrationsError, setIntegrationsError] = useState<string | null>(null);
  const [integrationBusy, setIntegrationBusy] = useState<string | null>(null);
  const [agentRuns, setAgentRuns] = useState<AgentRun[]>([]);
  const [agentRunsLoading, setAgentRunsLoading] = useState(false);
  const [agentRunBusyId, setAgentRunBusyId] = useState<string | null>(null);
  const [models, setModels] = useState<ModelOption[]>([]);
  const [savingModels, setSavingModels] = useState(false);

  const [historyQuery, setHistoryQuery] = useState("");
  const [deletingThreadId, setDeletingThreadId] = useState<string | null>(null);
  const [returnScreen, setReturnScreen] = useState<Screen>("purechat");

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loginError, setLoginError] = useState<string | null>(null);
  const [signingIn, setSigningIn] = useState(false);
  const integrationsVisibleRef = useRef(false);

  const listRef = useRef<HTMLDivElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);
  const streamRef = useRef<{ threadId: string; close: () => void } | null>(null);

  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  useEffect(() => {
    threadRef.current = thread;
  }, [thread]);

  useEffect(() => {
    integrationsVisibleRef.current = screen === "integrations";
  }, [screen]);

  const upsertThread = useCallback((nextThread: ChatThread) => {
    setState((prev) => {
      const exists = prev.threads.some((existing) => existing.id === nextThread.id);
      const threads = exists
        ? prev.threads.map((existing) => (existing.id === nextThread.id ? nextThread : existing))
        : [nextThread, ...prev.threads];
      const next = { ...prev, threads };
      stateRef.current = next;
      saveAppState(next);
      return next;
    });
  }, []);

  const applyEventsToThread = useCallback((threadId: string, events: ChatEvent[], replace = false) => {
    const target = stateRef.current.threads.find((candidate) => candidate.id === threadId)
      ?? (threadRef.current.id === threadId ? threadRef.current : null);
    if (!target) return;
    const start = replace
      ? { ...target, entries: [], activities: [], lastSeq: 0 }
      : target;
    const nextThread = events.reduce((current, event) => reduceChatEvent(current, event), start);
    upsertThread(nextThread);
    if (threadRef.current.id === threadId) {
      setThread(nextThread);
      threadRef.current = nextThread;
      if (nextThread.entries.some((entry) => entry.type === "assistant" && !entry.completedAt)) {
        setBusy(true);
      } else {
        setBusy(false);
      }
    }
  }, [upsertThread]);

  // Validate any stored session on load so a refresh keeps the user signed in.
  useEffect(() => {
    const existing = loadAuthSession();
    if (!existing) {
      setBooting(false);
      return;
    }
    fetchCurrentUser(existing.token)
      .then((validated) => {
        const restored = restorePreferredThread(stateRef.current.threads);
        const restoredThread = restored?.thread ?? createThread("chat");
        saveAuthSession(validated);
        setSession(validated);
        setThread(restoredThread);
        threadRef.current = restoredThread;
        setScreen(readLaunchScreen() === "integrations" ? "integrations" : (restored?.screen ?? screenForThread(restoredThread)));
        fetchGitHubStatus(validated.token).then((status) => setGitHubStatus(status)).catch(() => setGitHubStatus(null));
      })
      .catch(() => saveAuthSession(null))
      .finally(() => setBooting(false));
  }, []);

  const loadIntegrations = useCallback(
    async (options?: { clearOnFailure?: boolean; silent?: boolean }) => {
      if (!session) return null;
      const silent = Boolean(options?.silent);
      if (!silent) {
        setIntegrationsLoading(true);
        setIntegrationsError(null);
      }
      try {
        const next = await fetchIntegrations(session.token);
        if (!silent || integrationsVisibleRef.current) {
          setIntegrations(next);
        }
        return next;
      } catch (e) {
        if ((!silent || integrationsVisibleRef.current) && options?.clearOnFailure) {
          setIntegrations([]);
        }
        if (!silent || integrationsVisibleRef.current) {
          setIntegrationsError(e instanceof Error ? e.message : "Could not refresh integrations.");
        }
        return null;
      } finally {
        if (!silent) {
          setIntegrationsLoading(false);
        }
      }
    },
    [session]
  );

  useEffect(() => {
    if (!session || screen !== "integrations") return;
    void loadIntegrations({ clearOnFailure: true }).finally(() => {
      if (readLaunchScreen() === "integrations") {
        clearLaunchScreen();
      }
    });
  }, [session, screen, loadIntegrations]);

  useEffect(() => {
    if (!session || screen !== "integrations") return;
    const refresh = () => {
      if (document.visibilityState !== "visible") return;
      void loadIntegrations();
    };
    window.addEventListener("focus", refresh);
    document.addEventListener("visibilitychange", refresh);
    return () => {
      window.removeEventListener("focus", refresh);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [session, screen, loadIntegrations]);

  useEffect(() => {
    if (!session) return;
    fetchModels(session.token)
      .then((next) => setModels(next))
      .catch(() => setModels([]));
  }, [session]);

  useEffect(() => {
    if (!session) return;
    fetchGitHubStatus(session.token)
      .then((status) => setGitHubStatus(status))
      .catch(() => setGitHubStatus(null));
  }, [session]);

  const hydrateThreadFromEvents = useCallback(async (candidate: ChatThread, afterSeq?: number) => {
    if (!session) return;
    try {
      const events = await fetchChatSessionEvents(session.token, candidate.id, afterSeq ?? 0);
      if (!events.length && afterSeq == null) {
        if (candidate.entries.length || (candidate.activities?.length ?? 0)) {
          // The server has no persisted events for this session yet (e.g. a thread restored
          // from local storage whose first message was never accepted). Keep the local
          // transcript instead of wiping it; a real server-side history will replace it.
          return;
        }
        const emptyThread = { ...candidate, entries: [], activities: [], lastSeq: 0 };
        upsertThread(emptyThread);
        if (threadRef.current.id === candidate.id) {
          setThread(emptyThread);
          threadRef.current = emptyThread;
          setBusy(false);
        }
        return;
      }
      applyEventsToThread(candidate.id, events, afterSeq == null);
    } catch (error) {
      // Leave local state as-is on transient sync failures, but surface the cause for debugging.
      console.warn("Failed to hydrate chat thread from events", error);
    }
  }, [session, applyEventsToThread, upsertThread]);

  const ensureStream = useCallback((candidate: ChatThread, forceRestart = false) => {
    if (!session) return;
    if (!forceRestart && streamRef.current?.threadId === candidate.id) {
      return;
    }
    streamRef.current?.close();
    streamRef.current = {
      threadId: candidate.id,
      close: openChatEventStream(session.token, candidate.id, candidate.lastSeq ?? 0, {
        onEvent: (event) => applyEventsToThread(candidate.id, [event]),
        onStatus: (status) => {
          const target = stateRef.current.threads.find((item) => item.id === candidate.id)
            ?? (threadRef.current.id === candidate.id ? threadRef.current : null);
          if (!target) return;
          const connectionStatus: ChatThread["connectionStatus"] = status === "closed" ? "idle" : status;
          const nextThread: ChatThread = {
            ...target,
            connectionStatus
          };
          upsertThread(nextThread);
          if (threadRef.current.id === candidate.id) {
            setThread(nextThread);
            threadRef.current = nextThread;
          }
        },
        onError: () => {
          const target = stateRef.current.threads.find((item) => item.id === candidate.id)
            ?? (threadRef.current.id === candidate.id ? threadRef.current : null);
          if (!target) return;
          const nextThread: ChatThread = { ...target, connectionStatus: "error" };
          upsertThread(nextThread);
          if (threadRef.current.id === candidate.id) {
            setThread(nextThread);
            threadRef.current = nextThread;
          }
        }
      }).close
    };
  }, [session, applyEventsToThread, upsertThread]);

  useEffect(() => {
    if (!session) return;
    void hydrateThreadFromEvents(threadRef.current).then(() => ensureStream(threadRef.current));
  }, [session, thread.id, hydrateThreadFromEvents, ensureStream]);

  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [thread.entries]);

  useEffect(() => {
    const enabled = models.filter((model) => model.enabled);
    if (!enabled.length) return;
    const selected = enabled.find((model) => model.id === thread.modelId) ?? enabled[0];
    const nextReasoning = thread.reasoningEffort && selected.reasoningOptions.includes(thread.reasoningEffort)
      ? thread.reasoningEffort
      : defaultReasoningFor(selected);
    if (thread.modelId === selected.id && thread.reasoningEffort === nextReasoning) return;
    setThread((current) => ({
      ...current,
      modelId: selected.id,
      reasoningEffort: nextReasoning
    }));
  }, [models, thread.modelId, thread.reasoningEffort]);

  useEffect(() => {
    upsertThread(thread);
  }, [thread, upsertThread]);

  useEffect(() => {
    if (!session) return;
    if (screen === "chat" || screen === "purechat") {
      saveActiveThreadRestore(thread.id, screen);
      return;
    }
    if (screen === "login") {
      clearActiveThreadRestore();
    }
  }, [session, thread.id, screen]);

  useEffect(() => {
    if (!session) return;
    const handleVisibility = () => {
      if (document.visibilityState !== "visible") return;
      const current = threadRef.current;
      void hydrateThreadFromEvents(current, current.lastSeq ?? 0).then(() => ensureStream(threadRef.current, true));
    };
    document.addEventListener("visibilitychange", handleVisibility);
    window.addEventListener("focus", handleVisibility);
    return () => {
      document.removeEventListener("visibilitychange", handleVisibility);
      window.removeEventListener("focus", handleVisibility);
    };
  }, [session, hydrateThreadFromEvents, ensureStream]);

  useEffect(() => () => {
    streamRef.current?.close();
    streamRef.current = null;
  }, []);

  const refreshFiles = useCallback(
    async (sandboxId?: string) => {
      const id = sandboxId ?? threadRef.current.sandboxId;
      if (!id || !session) return;
      try {
        setFiles(await fetchSandboxFiles(session.token, id));
      } catch {
        setFiles([]);
      }
    },
    [session]
  );

  useEffect(() => {
    if (!session || screen !== "chat" || !thread.sandboxId) {
      setFiles([]);
      return;
    }
    setFiles([]);
    void refreshFiles(thread.sandboxId);
  }, [session, screen, thread.sandboxId, refreshFiles]);

  const signIn = useCallback(async () => {
    if (!username.trim() || !password.trim() || signingIn) return;
    setSigningIn(true);
    setLoginError(null);
    try {
      const validated = await loginRequest(username.trim(), password);
      saveAuthSession(validated);
      setSession(validated);
      setThread(createThread("chat"));
      setScreen(readLaunchScreen() === "integrations" ? "integrations" : "purechat");
      fetchModels(validated.token).then((next) => setModels(next)).catch(() => setModels([]));
      fetchGitHubStatus(validated.token).then((status) => setGitHubStatus(status)).catch(() => setGitHubStatus(null));
      setPassword("");
    } catch (e) {
      setLoginError(e instanceof Error ? e.message : "Sign in failed.");
    } finally {
      setSigningIn(false);
    }
  }, [username, password, signingIn]);

  const startChat = useCallback(() => {
    setThread(createThread("chat"));
    setFiles([]);
    setWsOpen(true);
    setDraftAttachment(null);
    setBugReportNotice(null);
    setError(null);
    setHistoryQuery("");
    setScreen("purechat");
    setMenuOpen(false);
  }, []);

  const startSandbox = useCallback(async () => {
    if (!session) return;
    setMenuOpen(false);
    setHistoryQuery("");
    setBugReportNotice(null);
    setError(null);
    setFiles([]);
    setWsOpen(true);
    setDraftAttachment(null);
    setBusy(true);
    try {
      const sandbox = await createSandbox(session.token);
      setThread(createThread("sandbox", { sandboxId: sandbox.id }));
      setScreen("chat");
      void refreshFiles(sandbox.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start a sandbox.");
      setThread(createThread("sandbox"));
      setScreen("chat");
    } finally {
      setBusy(false);
    }
  }, [session, refreshFiles]);

  const openHistory = useCallback(
    (item: ChatThread) => {
      setThread(item);
      void hydrateThreadFromEvents(item).then(() => ensureStream(item));
      setDraftAttachment(null);
      setBugReportNotice(null);
      setError(null);
      setMenuOpen(false);
      if (item.kind === "sandbox") {
        setScreen("chat");
        setFiles([]);
        setWsOpen(true);
        if (item.sandboxId) void refreshFiles(item.sandboxId);
      } else if (item.kind === "github") {
        setScreen("chat");
        setFiles([]);
        setWsOpen(false);
        if (item.sandboxId) void refreshFiles(item.sandboxId);
      } else {
        setScreen("purechat");
      }
    },
    [refreshFiles, hydrateThreadFromEvents, ensureStream]
  );

  const deleteThread = useCallback(
    async (item: ChatThread) => {
      if (deletingThreadId) return;
      const confirmed = typeof window === "undefined"
        ? true
        : window.confirm(`Delete “${item.title}”? This also removes any sandbox created for it.`);
      if (!confirmed) return;

      setDeletingThreadId(item.id);
      setError(null);
      try {
        // Tear down the sandbox first so a backend failure leaves the chat in the list to retry.
        if (item.sandboxId && session) {
          await deleteSandbox(session.token, item.sandboxId);
        }
        if (session) {
          await deleteThreadHistory(session.token, item.id);
        }

        setState((prev) => {
          const next = removeThread(prev, item.id);
          stateRef.current = next;
          saveAppState(next);
          return next;
        });

        // If the deleted conversation is the one currently open, drop back to a fresh chat.
        if (threadRef.current.id === item.id) {
          const fresh = createThread("chat");
          setThread(fresh);
          threadRef.current = fresh;
          setFiles([]);
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : "Could not delete this conversation.");
      } finally {
        setDeletingThreadId(null);
      }
    },
    [deletingThreadId, session]
  );

  const openIntegrations = useCallback(async () => {
    setReturnScreen(screen === "integrations" ? returnScreen : screen);
    setScreen("integrations");
    setMenuOpen(false);
    setBugReportNotice(null);
    if (!session) return;
    await loadIntegrations({ clearOnFailure: true });
  }, [screen, returnScreen, session, loadIntegrations]);

  const pollGoogleIntegrationRefresh = useCallback(async () => {
    if (!session) return;
    for (let attempt = 0; attempt < 20; attempt += 1) {
      if (!integrationsVisibleRef.current) {
        return;
      }
      const next = await loadIntegrations({ silent: true });
      if (next?.find((integration) => integration.id === "google")?.connected) {
        return;
      }
      await delay(1500);
    }
  }, [session, loadIntegrations]);

  const openSettings = useCallback(async () => {
    setReturnScreen(screen === "settings" ? returnScreen : screen);
    setScreen("settings");
    setMenuOpen(false);
    setBugReportNotice(null);
    if (!session) return;
    try {
      setModels(await fetchModels(session.token));
    } catch {
      setModels([]);
    }
  }, [screen, returnScreen, session]);

  const loadAgentRuns = useCallback(async () => {
    if (!session) return;
    setAgentRunsLoading(true);
    try {
      setAgentRuns(await fetchAgentRuns(session.token));
    } catch {
      // Keep the last known runs on a transient failure so the 3s poll doesn't
      // flicker the list to empty when a single request fails.
    } finally {
      setAgentRunsLoading(false);
    }
  }, [session]);

  const openAgentThreads = useCallback(async () => {
    setReturnScreen(screen === "agent-threads" ? returnScreen : screen);
    setScreen("agent-threads");
    setMenuOpen(false);
    setBugReportNotice(null);
    await loadAgentRuns();
  }, [screen, returnScreen, loadAgentRuns]);

  const cancelRun = useCallback(async (id: string) => {
    if (!session) return;
    setAgentRunBusyId(id);
    try {
      await cancelAgentRun(session.token, id);
      setAgentRuns((current) => current.map((run) => run.id === id ? { ...run, cancellationRequested: true } : run));
      await loadAgentRuns();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not cancel agent thread.");
    } finally {
      setAgentRunBusyId(null);
    }
  }, [session, loadAgentRuns]);

  useEffect(() => {
    if (!session || screen !== "agent-threads") return;
    void loadAgentRuns();
    const id = window.setInterval(() => {
      void loadAgentRuns();
    }, 3000);
    return () => window.clearInterval(id);
  }, [session, screen, loadAgentRuns]);

  const toggleIntegration = useCallback(
    async (integration: Integration) => {
      if (!session || (integration.id !== "google" && integration.id !== "github")) return;
      setIntegrationBusy(integration.id);
      try {
        if (integration.id === "google") {
          if (integration.connected) {
            await disconnectGoogle(session.token);
            setIntegrations((current) => current.map((item) => (
              item.id === "google" ? { ...item, connected: false } : item
            )));
          } else {
            const authUrl = await reconnectGoogle(session.token, window.location.href);
            if (authUrl) {
              window.open(authUrl, "_blank", "noopener");
              void pollGoogleIntegrationRefresh();
            }
          }
        } else if (integration.id === "github") {
          if (integration.connected) {
            await disconnectGitHub(session.token);
          } else {
            const response = await connectGitHub(session.token, window.location.href);
            const installUrl = response.installUrl;
            if (installUrl) {
              window.location.assign(installUrl);
              return;
            }
            setError(
              typeof response.status?.message === "string" && response.status.message
                ? response.status.message
                : integration.message || "GitHub connect is unavailable until the GitHub App is configured."
            );
            await loadIntegrations();
            return;
          }
        }
        await loadIntegrations();
        setGitHubStatus(await fetchGitHubStatus(session.token));
      } catch (e) {
        setError(e instanceof Error ? e.message : "Integration update failed.");
      } finally {
        setIntegrationBusy(null);
      }
    },
    [session]
  );

  const reconnectIntegration = useCallback(
    async (integration: Integration) => {
      if (!session || (integration.id !== "google" && integration.id !== "github")) return;
      setIntegrationBusy(integration.id);
      try {
        if (integration.id === "google") {
          const authUrl = await reconnectGoogle(session.token, window.location.href);
          if (authUrl) {
            window.open(authUrl, "_blank", "noopener");
            void pollGoogleIntegrationRefresh();
          } else {
            await loadIntegrations();
          }
        } else if (integration.id === "github") {
          const response = await connectGitHub(session.token, window.location.href);
          const installUrl = response.installUrl;
          if (installUrl) {
            window.location.assign(installUrl);
            return;
          }
          setError(
            typeof response.status?.message === "string" && response.status.message
              ? response.status.message
              : integration.message || "GitHub connect is unavailable until the GitHub App is configured."
          );
        }
        await loadIntegrations();
        setGitHubStatus(await fetchGitHubStatus(session.token));
      } catch (e) {
        setError(e instanceof Error ? e.message : "Integration update failed.");
      } finally {
        setIntegrationBusy(null);
      }
    },
    [session]
  );

  const send = useCallback(
    async (textArg?: string) => {
      const text = (textArg ?? draft).trim();
      const attachment = draftAttachment;
      if ((!text && !attachment) || busy || !session) return;
      const enabledModels = models.filter((model) => model.enabled);
      const selectedModel = enabledModels.find((model) => model.id === threadRef.current.modelId) ?? enabledModels[0];
      if (!selectedModel) {
        setError("Enable at least one model in Model settings before sending a message.");
        return;
      }
      const selectedReasoning = selectedModel.reasoningOptions.includes(threadRef.current.reasoningEffort ?? "")
        ? threadRef.current.reasoningEffort
        : defaultReasoningFor(selectedModel);

      let sandboxId = threadRef.current.sandboxId;
      if (!sandboxId && promptNeedsWorkspace(text)) {
        setBusy(true);
        setError(null);
        try {
          const sandbox = await createSandbox(session.token);
          sandboxId = sandbox.id;
          setThread((current) => ({ ...current, kind: current.kind === "github" ? "github" : "sandbox", sandboxId: sandbox.id }));
          setScreen("chat");
          setWsOpen(true);
          void refreshFiles(sandbox.id);
        } catch (e) {
          setBusy(false);
          setError(e instanceof Error ? e.message : "Could not start a sandbox for this task.");
          return;
        }
      }

      setDraft("");
      setDraftAttachment(null);
      setBusy(true);
      setBugReportNotice(null);
      setError(null);

      const currentThread = threadRef.current;
      const isFirst = !currentThread.entries.some((entry) => entry.type === "user");
      const nextThread: ChatThread = {
        ...currentThread,
        ...(sandboxId ? { kind: currentThread.kind === "github" ? "github" as const : "sandbox" as const, sandboxId } : {}),
        modelId: selectedModel.id,
        reasoningEffort: selectedReasoning,
        title: isFirst && currentThread.kind !== "github"
          ? getThreadTitle(text || attachment?.name || "Image attachment")
          : currentThread.title,
        updatedAt: nowIso()
      };
      setThread(nextThread);
      threadRef.current = nextThread;
      upsertThread(nextThread);

      try {
        await sendChatMessage(session.token, nextThread.id, {
          content: text,
          mode: nextThread.kind === "github" ? "GITHUB" : sandboxId ? "SANDBOX" : "CHAT",
          title: nextThread.title,
          attachments: attachment ? [attachment] : undefined,
          model: selectedModel.id,
          reasoningEffort: selectedReasoning,
          sandboxId
        });
        await hydrateThreadFromEvents(nextThread, currentThread.lastSeq ?? 0);
        ensureStream(nextThread);
      } catch (e) {
        setBusy(false);
        setError(e instanceof Error ? e.message : "The agent request failed.");
      } finally {
        if (sandboxId) void refreshFiles(sandboxId);
      }
    },
    [draft, draftAttachment, busy, session, refreshFiles, models, upsertThread, hydrateThreadFromEvents, ensureStream]
  );

  useEffect(() => {
    if (!pendingAutoPrompt || busy) return;
    setPendingAutoPrompt(null);
    void send(pendingAutoPrompt);
  }, [pendingAutoPrompt, busy, send]);

  const saveBugReport = useCallback(async () => {
    if (!session || reportingBug) return;
    setReportingBug(true);
    setError(null);
    setBugReportNotice(null);
    try {
      const activeThread = threadRef.current;
      const response = await reportBug(session.token, {
        sessionId: activeThread.id,
        title: activeThread.title,
        sandboxId: activeThread.sandboxId,
        thread: serializeThreadForBugReport(activeThread),
        clientContext: {
          exportedAt: nowIso(),
          screen,
          url: typeof window === "undefined" ? null : window.location.href,
          userAgent: typeof navigator === "undefined" ? null : navigator.userAgent,
          busy,
          activeAssistantId: [...activeThread.entries]
            .reverse()
            .find((entry) => entry.type === "assistant" && !entry.completedAt)?.id ?? null
        }
      });
      setBugReportNotice(`Saved to ${response.relativePath}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not save bug report.");
    } finally {
      setReportingBug(false);
    }
  }, [session, reportingBug, screen, busy]);

  const pickImage = useCallback(() => {
    imageInputRef.current?.click();
  }, []);

  const clearImage = useCallback(() => {
    setDraftAttachment(null);
    if (imageInputRef.current) {
      imageInputRef.current.value = "";
    }
  }, []);

  const onImageSelected = useCallback(async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      setError("Only image files are supported.");
      event.target.value = "";
      return;
    }
    if (file.size > MAX_IMAGE_BYTES) {
      setError("Images must be 5 MB or smaller.");
      event.target.value = "";
      return;
    }
    try {
      const dataUrl = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result ?? ""));
        reader.onerror = () => reject(new Error("Could not read the selected image."));
        reader.readAsDataURL(file);
      });
      setDraftAttachment({
        name: file.name,
        mimeType: file.type,
        dataUrl,
        size: file.size
      });
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not read the selected image.");
    } finally {
      event.target.value = "";
    }
  }, []);

  const toggleModelEnabled = useCallback((modelId: string) => {
    setModels((current) => current.map((model) => (model.id === modelId ? { ...model, enabled: !model.enabled } : model)));
  }, []);

  const saveModelPreferences = useCallback(async () => {
    if (!session || savingModels) return;
    const enabledModelIds = models.filter((model) => model.enabled).map((model) => model.id);
    if (enabledModelIds.length === 0) {
      setError("Enable at least one model before saving.");
      return;
    }
    setSavingModels(true);
    try {
      setModels(await saveEnabledModels(session.token, enabledModelIds));
      setScreen(returnScreen);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not save model settings.");
    } finally {
      setSavingModels(false);
    }
  }, [session, savingModels, models, returnScreen]);

  const openGitHubRepoSetup = useCallback(async () => {
    if (!session || !githubStatus?.active) return;
    setReturnScreen(screen === "github-repo" ? returnScreen : screen);
    setScreen("github-repo");
    setMenuOpen(false);
    setBugReportNotice(null);
    setError(null);
    setPendingAutoPrompt(null);
    setSelectedRepo("");
    setSelectedBranch("");
    setSelectedBugReportId("");
    setBranchOptions([]);
    setBugReports([]);
    setLoadingRepos(true);
    setLoadingBugReports(githubStatus?.active === true);
    try {
      const [repos, reports] = await Promise.all([
        fetchGitHubRepositories(session.token),
        githubStatus?.active ? fetchBugReports(session.token) : Promise.resolve([])
      ]);
      setRepoOptions(repos);
      setBugReports(reports);
    } catch (e) {
      setRepoOptions([]);
      setBugReports([]);
      setError(e instanceof Error ? e.message : "Could not load GitHub repositories.");
    } finally {
      setLoadingRepos(false);
      setLoadingBugReports(false);
    }
  }, [session, githubStatus?.active, screen, returnScreen]);

  const chooseRepo = useCallback(async (repoFullName: string) => {
    setSelectedRepo(repoFullName);
    setSelectedBranch("");
    setBranchOptions([]);
    if (!session || !repoFullName) return;
    setLoadingBranches(true);
    setError(null);
    try {
      const branches = await fetchGitHubBranches(session.token, repoFullName);
      setBranchOptions(branches);
      const defaultBranch = repoOptions.find((repo) => repo.fullName === repoFullName)?.defaultBranch;
      setSelectedBranch(resolvePreferredGitHubBranch(branches, defaultBranch));
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load GitHub branches.");
    } finally {
      setLoadingBranches(false);
    }
  }, [session, repoOptions]);

  useEffect(() => {
    if (!selectedRepo || !branchOptions.length) return;
    const defaultBranch = repoOptions.find((repo) => repo.fullName === selectedRepo)?.defaultBranch;
    const preferredBranch = resolvePreferredGitHubBranch(branchOptions, defaultBranch, selectedBranch);
    if (preferredBranch !== selectedBranch) {
      setSelectedBranch(preferredBranch);
    }
  }, [selectedRepo, selectedBranch, branchOptions, repoOptions]);

  const confirmGitHubRepo = useCallback(async () => {
    if (!session || !selectedRepo || !selectedBranch) return;
    const repo = repoOptions.find((item) => item.fullName === selectedRepo);
    if (!repo) return;
    const selectedBugReport = bugReports.find((item) => item.id === selectedBugReportId);
    setBusy(true);
    setError(null);
    try {
      const sandbox = await createGitHubSandbox(session.token, selectedRepo, selectedBranch, selectedBugReportId || undefined);
      setFiles([]);
      setWsOpen(false);
      setDraft("");
      setDraftAttachment(null);
      const nextThread = createThread("github", {
        sandboxId: sandbox.id,
        repoFullName: repo.fullName,
        repoName: repo.name,
        branchName: selectedBranch
      });
      setThread(nextThread);
      threadRef.current = nextThread;
      upsertThread(nextThread);
      setScreen("chat");
      if (selectedBugReport) {
        setPendingAutoPrompt(buildGitHubBugReportPrompt(selectedBugReport));
      }
      void refreshFiles(sandbox.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start a GitHub repo sandbox.");
    } finally {
      setBusy(false);
    }
  }, [session, selectedRepo, selectedBranch, selectedBugReportId, repoOptions, bugReports, refreshFiles, upsertThread]);

  const enabledModels = models.filter((model) => model.enabled);
  const activeModel = enabledModels.find((model) => model.id === thread.modelId) ?? enabledModels[0];
  const activeReasoning = activeModel?.reasoningOptions.includes(thread.reasoningEffort ?? "")
    ? thread.reasoningEffort
    : defaultReasoningFor(activeModel);

  if (booting) {
    return (
      <div className="mock-page">
        <div className="device-shell">
          <div className="chat-body">
            <div className="greeting">
              <p>Loading…</p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const fresh = thread.entries.length === 0;
  const name = session?.username ?? "there";

  return (
    <div className="mock-page">
      <div className="device-shell">
        {!session || screen === "login" ? (
          <LoginScreen
            username={username}
            password={password}
            error={loginError}
            busy={signingIn}
            onUser={setUsername}
            onPass={setPassword}
            onSignIn={signIn}
          />
        ) : screen === "chat" || screen === "purechat" ? (
          <>
            <AppHeader
              onMenu={() => setMenuOpen(true)}
              reportAction={{
                busy: reportingBug,
                onClick: saveBugReport
              }}
            />
            <input
              ref={imageInputRef}
              type="file"
              accept="image/*"
              onChange={onImageSelected}
              className="visually-hidden"
              tabIndex={-1}
            />
            {screen === "chat" ? (
              <FileTree
                sessionId={thread.id}
                files={files}
                wsOpen={wsOpen}
                onToggleWs={() => setWsOpen((current) => !current)}
                label={thread.kind === "github"
                  ? `${thread.repoName ?? thread.repoFullName ?? "repo"} · ${thread.branchName ?? "branch"}`
                  : `~/sandbox/${thread.id.slice(0, 8)}`}
                rootName={thread.kind === "github"
                  ? (thread.repoName ?? thread.repoFullName ?? "repo")
                  : `sandbox-${thread.id.slice(0, 8)}`}
                badge={thread.kind === "github" ? "github" : "sandbox"}
                defaultOpenDirectories={thread.kind !== "github"}
              />
            ) : null}
            {error ? <p className="login-error screen-pad">{error}</p> : null}
            {bugReportNotice ? <p className="screen-note screen-pad">{bugReportNotice}</p> : null}
            {fresh ? (
              <div className="chat-body">
                <Greeting name={name} onChip={send} />
              </div>
            ) : (
              <div className="chat-stack">
                <Messages entries={thread.entries} busy={busy} listRef={listRef} />
                <ActivityPanel activities={thread.activities ?? []} />
              </div>
            )}
            <InputBar
              value={draft}
              disabled={busy || enabledModels.length === 0}
              attachment={draftAttachment}
              models={enabledModels}
              selectedModelId={activeModel?.id}
              selectedReasoning={activeReasoning}
              onChange={setDraft}
              onModelChange={(modelId) => {
                const model = enabledModels.find((item) => item.id === modelId);
                setThread((current) => ({
                  ...current,
                  modelId,
                  reasoningEffort: defaultReasoningFor(model)
                }));
              }}
              onReasoningChange={(reasoningEffort) => {
                setThread((current) => ({ ...current, reasoningEffort }));
              }}
              onPickImage={pickImage}
              onClearImage={clearImage}
              onSend={() => send()}
            />
          </>
        ) : screen === "integrations" ? (
          <IntegrationsScreen
            integrations={integrations}
            loading={integrationsLoading}
            error={integrationsError}
            busyId={integrationBusy}
            onBack={() => setScreen(returnScreen)}
            onToggle={toggleIntegration}
            onReconnect={reconnectIntegration}
          />
        ) : screen === "github-repo" ? (
          <RepoSetupScreen
            busy={busy}
            loadingRepos={loadingRepos}
            loadingBranches={loadingBranches}
            loadingBugReports={loadingBugReports}
            repositories={repoOptions}
            branches={branchOptions}
            bugReports={bugReports}
            selectedRepo={selectedRepo}
            selectedBranch={selectedBranch}
            selectedBugReportId={selectedBugReportId}
            error={error}
            onBack={() => setScreen(returnScreen)}
            onRepoChange={chooseRepo}
            onBranchChange={setSelectedBranch}
            onBugReportChange={setSelectedBugReportId}
            onConfirm={confirmGitHubRepo}
          />
        ) : screen === "settings" ? (
          <SettingsScreen
            models={models}
            saving={savingModels}
            onBack={() => setScreen(returnScreen)}
            onToggle={toggleModelEnabled}
            onSave={saveModelPreferences}
          />
        ) : screen === "agent-threads" ? (
          <AgentThreadsScreen
            runs={agentRuns}
            threads={state.threads}
            busyRunId={agentRunBusyId}
            loading={agentRunsLoading}
            onBack={() => setScreen(returnScreen)}
            onCancel={cancelRun}
          />
        ) : (
          <HistoryScreen
            threads={state.threads}
            onMenu={() => setMenuOpen(true)}
            onOpen={openHistory}
            onDelete={deleteThread}
            onNew={startChat}
            deletingId={deletingThreadId}
            query={historyQuery}
            onQuery={setHistoryQuery}
          />
        )}

        {menuOpen && session ? (
          <MenuOverlay
            username={session.username}
            roles={session.roles}
            githubConnected={githubStatus?.active === true}
            onClose={() => setMenuOpen(false)}
            onSandbox={startSandbox}
            onGitHubRepo={openGitHubRepoSetup}
            onChat={startChat}
            onHistory={() => {
              setHistoryQuery("");
              setScreen("history");
              setMenuOpen(false);
            }}
            onAgentThreads={openAgentThreads}
            onIntegrations={openIntegrations}
            onSettings={openSettings}
          />
        ) : null}
      </div>
    </div>
  );
}
