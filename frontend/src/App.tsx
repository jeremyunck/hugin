import { useCallback, useEffect, useRef, useState, type ChangeEvent, type ReactNode, type RefObject } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  ArrowLeft,
  BatteryFull,
  Box,
  Check,
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
  Search,
  Send,
  Signal,
  SlidersHorizontal,
  Settings2,
  User,
  Wifi,
  X
} from "lucide-react";

import {
  buildAssistantEntry,
  buildPriorMessages,
  buildUserEntry,
  connectGitHub,
  createSandbox,
  createGitHubSandbox,
  createThread,
  disconnectGitHub,
  disconnectGoogle,
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
  saveEnabledModels,
  saveAppState,
  saveAuthSession,
  streamPrompt,
  syncThreadHistory,
  type StreamEvent
} from "./services/guildService";
import type {
  AppState,
  AuthSession,
  ChatAttachment,
  ChatEntry,
  ChatThread,
  FileNode,
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
  green: "#20A65A"
};

const CHIPS = [
  ["Summarize a document", "Summarize a document for me."],
  ["Analyze data", "Analyze this dataset and show key trends."],
  ["Write code", "Write a Python script to clean a CSV file."],
  ["Brainstorm ideas", "Brainstorm ideas for a product launch."],
  ["Show me tips", "Show me tips for getting the most out of Hugin."]
] as const;

type Screen = "login" | "chat" | "purechat" | "history" | "integrations" | "settings" | "github-repo";
const WORKSPACE_ACTION_RE =
  /\b(debug|fix|edit|change|update|inspect|investigate|search|grep|find|open|read|write|modify|patch|refactor|run|build|test|render)\b/i;
const WORKSPACE_TARGET_RE =
  /\b(code|repo|repository|file|files|folder|directory|project|frontend|backend|component|markdown|ui|function|class|css|html|typescript|javascript|java|python|bash|shell|command)\b/i;
const WORKSPACE_PATH_RE = /(^|\s)(\.\/|\/|~\/)[^\s]+/;

function entryId(prefix: string) {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
  }
  return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
}

function nowIso() {
  return new Date().toISOString();
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

function clearLaunchScreen() {
  if (typeof window === "undefined") return;
  const url = new URL(window.location.href);
  url.searchParams.delete("screen");
  url.searchParams.delete("github");
  url.searchParams.delete("installation_id");
  url.searchParams.delete("setup_action");
  window.history.replaceState({}, "", url.toString());
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

/** Folds a streamed agent event into the working thread, keyed by the active assistant entry. */
function applyStreamEvent(thread: ChatThread, assistantId: string, event: StreamEvent): { thread: ChatThread; assistantId: string } {
  const entries = thread.entries.slice();
  const idx = entries.findIndex((entry) => entry.id === assistantId);
  if (idx === -1) return { thread, assistantId };
  const assistant = entries[idx] as Extract<ChatEntry, { type: "assistant" }>;
  let nextAssistantId = assistantId;

  switch (event.type) {
    case "token":
      entries[idx] = { ...assistant, content: assistant.content + event.text };
      break;
    case "reasoning":
      entries[idx] = { ...assistant, reasoning: assistant.reasoning + event.text };
      break;
    case "tool": {
      const toolEntry: ChatEntry = {
        id: entryId("entry-tool"),
        type: "tool",
        tool: {
          id: entryId("tool"),
          name: event.name,
          args: event.args,
          result: "",
          startedAt: nowIso()
        },
        createdAt: nowIso()
      };
      if (assistant.content.trim() || assistant.reasoning.trim()) {
        const nextAssistant = buildAssistantEntry();
        entries[idx] = { ...assistant, completedAt: assistant.completedAt ?? nowIso() };
        entries.splice(idx + 1, 0, toolEntry, nextAssistant);
        nextAssistantId = nextAssistant.id;
      } else {
        entries.splice(idx, 0, toolEntry);
      }
      break;
    }
    case "tool_result": {
      for (let i = entries.length - 1; i >= 0; i--) {
        const entry = entries[i];
        if (entry.type === "tool" && !entry.tool.finishedAt && entry.tool.name === event.name) {
          entries[i] = {
            ...entry,
            tool: { ...entry.tool, result: event.result, finishedAt: nowIso() }
          };
          break;
        }
      }
      break;
    }
    case "error":
      entries[idx] = { ...assistant, content: assistant.content || `⚠️ ${event.message}` };
      break;
    case "replace":
      entries[idx] = { ...assistant, content: event.content, completedAt: nowIso() };
      break;
    case "reset": {
      const resetIdx = entries.findIndex((entry) => entry.id === assistantId);
      if (resetIdx !== -1) {
        entries[resetIdx] = { ...assistant, content: "", reasoning: "" };
      }
      break;
    }
    case "done": {
      if (!assistant.content.trim() && !assistant.reasoning.trim()) {
        entries.splice(idx, 1);
      } else {
        entries[idx] = { ...assistant, completedAt: assistant.completedAt ?? nowIso() };
      }
      break;
    }
    default:
      break;
  }

  return {
    thread: { ...thread, updatedAt: nowIso(), entries },
    assistantId: nextAssistantId
  };
}

function StatusBar() {
  return (
    <div className="status-bar">
      <span className="status-time">9:41</span>
      <div className="status-notch" />
      <div className="status-icons">
        <Signal size={16} strokeWidth={2.4} />
        <Wifi size={16} strokeWidth={2.4} />
        <BatteryFull size={22} strokeWidth={1.8} />
      </div>
    </div>
  );
}

function AppHeader({ onMenu }: { onMenu: () => void }) {
  return (
    <div className="app-header">
      <div className="brand">
        <img src={LOGO} alt="Hugin" className="brand-logo" />
        <span className="brand-text">HUGIN</span>
      </div>
      <button type="button" className="icon-button" onClick={onMenu} aria-label="Open menu">
        <Menu size={22} strokeWidth={2} />
      </button>
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

function FileTree(props: {
  sessionId: string;
  files: FileNode[];
  wsOpen: boolean;
  onToggleWs: () => void;
  label: string;
  badge: string;
  defaultOpenDirectories: boolean;
}) {
  const { sessionId, files, wsOpen, onToggleWs, label, badge, defaultOpenDirectories } = props;

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
        <span>workspace</span>
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
  repositories: GitHubRepository[];
  branches: GitHubBranch[];
  selectedRepo: string;
  selectedBranch: string;
  error: string | null;
  onBack: () => void;
  onRepoChange: (value: string) => void;
  onBranchChange: (value: string) => void;
  onConfirm: () => void;
}) {
  const {
    busy,
    loadingRepos,
    loadingBranches,
    repositories,
    branches,
    selectedRepo,
    selectedBranch,
    error,
    onBack,
    onRepoChange,
    onBranchChange,
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

function Messages({
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

        if (entry.type === "tool") {
          return (
            <div key={entry.id} className="message-row message-row-assistant fade-in">
              <details className="tool-event">
                <summary className="assistant-event">
                  {entry.tool.finishedAt ? (
                    <Check size={15} strokeWidth={3} color={COLORS.green} />
                  ) : (
                    <TypingDots />
                  )}
                  <span>
                    Used <span className="mono">{entry.tool.name}</span>
                  </span>
                </summary>
                <div className="tool-event-body">
                  <div className="tool-event-section">
                    <span className="tool-event-label">Input</span>
                    <pre>{entry.tool.args || "(empty)"}</pre>
                  </div>
                  <div className="tool-event-section">
                    <span className="tool-event-label">Output</span>
                    <pre>{entry.tool.result || (entry.tool.finishedAt ? "(empty)" : "Running…")}</pre>
                  </div>
                </div>
              </details>
            </div>
          );
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
  onIntegrations: () => void;
  onSettings: () => void;
}) {
  const { username, roles, githubConnected, onClose, onSandbox, onGitHubRepo, onChat, onHistory, onIntegrations, onSettings } = props;
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

function HistoryScreen(props: {
  threads: ChatThread[];
  onMenu: () => void;
  onOpen: (thread: ChatThread) => void;
  onNew: () => void;
  query: string;
  onQuery: (value: string) => void;
}) {
  const { threads, onMenu, onOpen, onNew, query, onQuery } = props;
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
                  <button key={thread.id} type="button" className="history-card" onClick={() => onOpen(thread)}>
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
  busyId: string | null;
  onBack: () => void;
  onToggle: (integration: Integration) => void;
}) {
  const { integrations, busyId, onBack, onToggle } = props;

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
      </div>

      <div className="integrations-list">
        <div className="history-group-label">SERVICES</div>
        {integrations.length === 0 ? (
          <p className="history-empty">No integrations available.</p>
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
                    <button
                      type="button"
                      className="secondary-button"
                      disabled={busyId === integration.id}
                      onClick={() => onToggle(integration)}
                    >
                      {busyId === integration.id ? "…" : "Disconnect"}
                    </button>
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
  const enabledCount = models.filter((model) => model.enabled).length;

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

      <div className="integrations-list">
        <div className="history-group-label">AVAILABLE MODELS</div>
        {models.length === 0 ? (
          <p className="history-empty">No models available.</p>
        ) : (
          models.map((model) => (
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
  const activeAssistantIdsRef = useRef(new Map<string, string>());

  const [draft, setDraft] = useState("");
  const [draftAttachment, setDraftAttachment] = useState<ChatAttachment | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [files, setFiles] = useState<FileNode[]>([]);
  const [wsOpen, setWsOpen] = useState(true);
  const [githubStatus, setGitHubStatus] = useState<GitHubStatus | null>(null);
  const [repoOptions, setRepoOptions] = useState<GitHubRepository[]>([]);
  const [branchOptions, setBranchOptions] = useState<GitHubBranch[]>([]);
  const [selectedRepo, setSelectedRepo] = useState("");
  const [selectedBranch, setSelectedBranch] = useState("");
  const [loadingRepos, setLoadingRepos] = useState(false);
  const [loadingBranches, setLoadingBranches] = useState(false);

  const [integrations, setIntegrations] = useState<Integration[]>([]);
  const [integrationBusy, setIntegrationBusy] = useState<string | null>(null);
  const [models, setModels] = useState<ModelOption[]>([]);
  const [savingModels, setSavingModels] = useState(false);

  const [historyQuery, setHistoryQuery] = useState("");
  const [returnScreen, setReturnScreen] = useState<Screen>("purechat");

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loginError, setLoginError] = useState<string | null>(null);
  const [signingIn, setSigningIn] = useState(false);

  const listRef = useRef<HTMLDivElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  useEffect(() => {
    threadRef.current = thread;
  }, [thread]);

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

  const applyEventToThread = useCallback((threadId: string, event: StreamEvent) => {
    let nextThread: ChatThread | null = null;
    const target = stateRef.current.threads.find((candidate) => candidate.id === threadId)
      ?? (threadRef.current.id === threadId ? threadRef.current : null);
    const assistantId = activeAssistantIdsRef.current.get(threadId);
    if (!target || !assistantId) return;
    const result = applyStreamEvent(target, assistantId, event);
    activeAssistantIdsRef.current.set(threadId, result.assistantId);
    nextThread = result.thread;
    upsertThread(nextThread);
    if (threadRef.current.id === threadId) {
      setThread(nextThread);
      threadRef.current = nextThread;
    }
    if (event.type === "done" || event.type === "error") {
      activeAssistantIdsRef.current.delete(threadId);
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
        saveAuthSession(validated);
        setSession(validated);
        setScreen(readLaunchScreen() === "integrations" ? "integrations" : "purechat");
        fetchGitHubStatus(validated.token).then((status) => setGitHubStatus(status)).catch(() => setGitHubStatus(null));
      })
      .catch(() => saveAuthSession(null))
      .finally(() => setBooting(false));
  }, []);

  useEffect(() => {
    if (!session || screen !== "integrations") return;
    fetchIntegrations(session.token)
      .then((next) => setIntegrations(next))
      .catch(() => setIntegrations([]))
      .finally(() => {
        if (readLaunchScreen() === "integrations") {
          clearLaunchScreen();
        }
      });
  }, [session, screen]);

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

  const syncThreadFromServer = useCallback(async (candidate: ChatThread) => {
    if (!session || !candidate.entries.some((entry) => entry.type === "user")) return;
    try {
      const next = await syncThreadHistory(session.token, candidate);
      upsertThread(next);
      if (threadRef.current.id === next.id) {
        setThread(next);
      }
    } catch {
      // Leave local state alone when the background resync cannot reach the server.
    }
  }, [session, upsertThread]);

  useEffect(() => {
    if (busy) return;
    void syncThreadFromServer(thread);
  }, [busy, thread.id, syncThreadFromServer]);

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
    if (!thread.entries.some((entry) => entry.type === "user")) return;
    upsertThread(thread);
  }, [thread, upsertThread]);

  useEffect(() => {
    if (!session) return;
    const handleVisibility = () => {
      if (document.visibilityState !== "visible") return;
      const activeThreadIds = new Set(activeAssistantIdsRef.current.keys());
      for (const threadId of activeThreadIds) {
        const candidate = stateRef.current.threads.find((item) => item.id === threadId)
          ?? (threadRef.current.id === threadId ? threadRef.current : null);
        if (candidate) {
          void syncThreadFromServer(candidate);
        }
      }
    };
    document.addEventListener("visibilitychange", handleVisibility);
    window.addEventListener("focus", handleVisibility);
    return () => {
      document.removeEventListener("visibilitychange", handleVisibility);
      window.removeEventListener("focus", handleVisibility);
    };
  }, [session, syncThreadFromServer]);

  const refreshFiles = useCallback(
    async (sandboxId?: string) => {
      const id = sandboxId ?? threadRef.current.sandboxId;
      if (!id || !session) return;
      try {
        setFiles(await fetchSandboxFiles(session.token, id));
      } catch {
        // Best-effort; leave the previous tree in place.
      }
    },
    [session]
  );

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
    setError(null);
    setHistoryQuery("");
    setScreen("purechat");
    setMenuOpen(false);
  }, []);

  const startSandbox = useCallback(async () => {
    if (!session) return;
    setMenuOpen(false);
    setHistoryQuery("");
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
      void syncThreadFromServer(item);
      setDraftAttachment(null);
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
    [refreshFiles, syncThreadFromServer]
  );

  const openIntegrations = useCallback(async () => {
    setReturnScreen(screen === "integrations" ? returnScreen : screen);
    setScreen("integrations");
    setMenuOpen(false);
    if (!session) return;
    try {
      setIntegrations(await fetchIntegrations(session.token));
    } catch {
      setIntegrations([]);
    }
  }, [screen, returnScreen, session]);

  const openSettings = useCallback(async () => {
    setReturnScreen(screen === "settings" ? returnScreen : screen);
    setScreen("settings");
    setMenuOpen(false);
    if (!session) return;
    try {
      setModels(await fetchModels(session.token));
    } catch {
      setModels([]);
    }
  }, [screen, returnScreen, session]);

  const toggleIntegration = useCallback(
    async (integration: Integration) => {
      if (!session || (integration.id !== "google" && integration.id !== "github")) return;
      setIntegrationBusy(integration.id);
      try {
        if (integration.id === "google") {
          if (integration.connected) {
            await disconnectGoogle(session.token);
          } else {
            const authUrl = await reconnectGoogle(session.token, window.location.href);
            if (authUrl) window.open(authUrl, "_blank", "noopener");
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
            setIntegrations(await fetchIntegrations(session.token));
            return;
          }
        }
        setIntegrations(await fetchIntegrations(session.token));
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
      setError(null);

      const userEntry = buildUserEntry(text, attachment ? [attachment] : undefined);
      const assistant = buildAssistantEntry();
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
        updatedAt: nowIso(),
        entries: [...currentThread.entries, userEntry, assistant]
      };
      activeAssistantIdsRef.current.set(nextThread.id, assistant.id);
      setThread(nextThread);
      threadRef.current = nextThread;
      upsertThread(nextThread);

      try {
        const priorMessages = buildPriorMessages(currentThread);
        await streamPrompt(
          session.token,
          {
            threadId: nextThread.id,
            prompt: text,
            attachments: attachment ? [attachment] : undefined,
            priorMessages,
            model: selectedModel.id,
            reasoningEffort: selectedReasoning,
            sandboxId
          },
          { onEvent: (event) => applyEventToThread(nextThread.id, event) }
        );
      } catch (e) {
        const message = e instanceof Error ? e.message : "The agent request failed.";
        applyEventToThread(nextThread.id, { type: "error", message });
      } finally {
        setBusy(false);
        if (sandboxId) void refreshFiles(sandboxId);
      }
    },
    [draft, draftAttachment, busy, session, refreshFiles, models, upsertThread, applyEventToThread]
  );

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
    setError(null);
    setSelectedRepo("");
    setSelectedBranch("");
    setBranchOptions([]);
    setLoadingRepos(true);
    try {
      setRepoOptions(await fetchGitHubRepositories(session.token));
    } catch (e) {
      setRepoOptions([]);
      setError(e instanceof Error ? e.message : "Could not load GitHub repositories.");
    } finally {
      setLoadingRepos(false);
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
      setSelectedBranch(branches.find((branch) => branch.name === defaultBranch)?.name ?? branches[0]?.name ?? "");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load GitHub branches.");
    } finally {
      setLoadingBranches(false);
    }
  }, [session, repoOptions]);

  const confirmGitHubRepo = useCallback(async () => {
    if (!session || !selectedRepo || !selectedBranch) return;
    const repo = repoOptions.find((item) => item.fullName === selectedRepo);
    if (!repo) return;
    setBusy(true);
    setError(null);
    try {
      const sandbox = await createGitHubSandbox(session.token, selectedRepo, selectedBranch);
      setFiles([]);
      setWsOpen(false);
      setDraft("");
      setDraftAttachment(null);
      setThread(createThread("github", {
        sandboxId: sandbox.id,
        repoFullName: repo.fullName,
        repoName: repo.name,
        branchName: selectedBranch
      }));
      setScreen("chat");
      void refreshFiles(sandbox.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start a GitHub repo sandbox.");
    } finally {
      setBusy(false);
    }
  }, [session, selectedRepo, selectedBranch, repoOptions, refreshFiles]);

  const enabledModels = models.filter((model) => model.enabled);
  const activeModel = enabledModels.find((model) => model.id === thread.modelId) ?? enabledModels[0];
  const activeReasoning = activeModel?.reasoningOptions.includes(thread.reasoningEffort ?? "")
    ? thread.reasoningEffort
    : defaultReasoningFor(activeModel);

  if (booting) {
    return (
      <div className="mock-page">
        <div className="device-shell">
          <StatusBar />
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
        <StatusBar />

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
            <AppHeader onMenu={() => setMenuOpen(true)} />
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
                badge={thread.kind === "github" ? "github" : "sandbox"}
                defaultOpenDirectories={thread.kind !== "github"}
              />
            ) : null}
            {error ? <p className="login-error screen-pad">{error}</p> : null}
            {fresh ? (
              <div className="chat-body">
                <Greeting name={name} onChip={send} />
              </div>
            ) : (
              <Messages entries={thread.entries} busy={busy} listRef={listRef} />
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
            busyId={integrationBusy}
            onBack={() => setScreen(returnScreen)}
            onToggle={toggleIntegration}
          />
        ) : screen === "github-repo" ? (
          <RepoSetupScreen
            busy={busy}
            loadingRepos={loadingRepos}
            loadingBranches={loadingBranches}
            repositories={repoOptions}
            branches={branchOptions}
            selectedRepo={selectedRepo}
            selectedBranch={selectedBranch}
            error={error}
            onBack={() => setScreen(returnScreen)}
            onRepoChange={chooseRepo}
            onBranchChange={setSelectedBranch}
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
        ) : (
          <HistoryScreen
            threads={state.threads}
            onMenu={() => setMenuOpen(true)}
            onOpen={openHistory}
            onNew={startChat}
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
            onIntegrations={openIntegrations}
            onSettings={openSettings}
          />
        ) : null}
      </div>
    </div>
  );
}
