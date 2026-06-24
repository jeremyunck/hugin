import { useCallback, useEffect, useRef, useState, type ChangeEvent } from "react";
import {
  ArrowLeft,
  Bot,
  ChevronRight,
  Github,
  History,
  Lock,
  MessageCirclePlus,
  Network,
  Puzzle,
  Search,
  Settings,
  Settings2,
  User,
  X
} from "lucide-react";

import {
  connectGitHub,
  createGitHubSandbox,
  createThread,
  deleteSandbox,
  deleteThreadHistory,
  disconnectGitHub,
  disconnectGoogle,
  cancelAgentRun,
  fetchBugReports,
  fetchAgentRuns,
  fetchAgentWorkspaceFiles,
  fetchGitHubBranches,
  fetchGitHubRepositories,
  fetchGitHubStatus,
  fetchModels,
  fetchCurrentUser,
  fetchIntegrations,
  fetchSandboxFiles,
  formatTimestamp,
  getThreadTitle,
  loadAuthSession,
  login as loginRequest,
  reconnectGoogle,
  reportBug,
  saveEnabledModels,
  saveAuthSession
} from "./services/guildService";
import type {
  AuthSession,
  AgentRun,
  ChatAttachment,
  ChatThread,
  FileNode,
  BugReportSummary,
  GitHubBranch,
  GitHubRepository,
  GitHubStatus,
  Integration,
  ModelOption
} from "./lib/types";
import { COLORS } from "./lib/theme";
import { defaultReasoningFor } from "./lib/format";
import {
  DEFAULT_REQUEST_TIMEOUT_SECONDS,
  FONT_SIZE_OPTIONS,
  MAX_TOOL_CALLS_MAX,
  MAX_TOOL_CALLS_MIN,
  REQUEST_TIMEOUT_MAX,
  REQUEST_TIMEOUT_MIN,
  applyFontSize,
  loadPreferences,
  normalizeMaxToolCalls,
  normalizeRequestTimeout,
  resolveDefaultModelId,
  savePreferences,
  type AppPreferences,
  type FontSizeId
} from "./lib/preferences";
import { useChatSessionStore } from "./stores/chatSessionStore";
import { AppHeader } from "./components/AppHeader";
import { WorkspacePanel } from "./components/WorkspacePanel";
import { ChatPanel } from "./components/chat/ChatPanel";
import { HistoryPanel } from "./components/HistoryPanel";
import { IntegrationPanel } from "./components/IntegrationPanel";

// Re-exports kept stable for tests and external imports after the Phase 2 refactor.
export { reduceChatEvent } from "./stores/chatEventReducer";
export { MessageList as Messages } from "./components/chat/MessageList";
export { WorkspacePanel as FileTree } from "./components/WorkspacePanel";
export { HistoryPanel as HistoryScreen } from "./components/HistoryPanel";

const LOGO = "/hugin-bird.jpg";

type Screen =
  | "login"
  | "chat"
  | "purechat"
  | "history"
  | "integrations"
  | "settings"
  | "preferences"
  | "github-repo"
  | "agent-threads";

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
    if (!parsed.threadId) return null;
    return parsed;
  } catch {
    return null;
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

const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

function screenForThread(thread: ChatThread): Screen {
  // Agent and Project threads carry a workspace file tree, so they use the "chat" screen; a plain
  // chat has no workspace and uses "purechat".
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
        screen: active.screen ?? screenForThread(match)
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
        <h1 className="screen-title integration-title">Project</h1>
        <p className="integration-subtitle">
          Pick a repository and branch, then Hugin will open a fresh workspace with a clean pull of that branch.
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
          {busy ? "Creating workspace…" : "Open project"}
        </button>
      </div>
    </>
  );
}

function MenuOverlay(props: {
  username: string;
  roles: string[];
  githubConnected: boolean;
  onClose: () => void;
  onAgent: () => void;
  onGitHubRepo: () => void;
  onChat: () => void;
  onHistory: () => void;
  onAgentThreads: () => void;
  onIntegrations: () => void;
  onSettings: () => void;
  onPreferences: () => void;
}) {
  const { username, roles, githubConnected, onClose, onAgent, onGitHubRepo, onChat, onHistory, onAgentThreads, onIntegrations, onSettings, onPreferences } = props;
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
          <button type="button" className="menu-item" onClick={onAgent}>
            <Bot size={18} strokeWidth={2} color={COLORS.ink} />
            <span>Agent</span>
          </button>
          {githubConnected ? (
            <button type="button" className="menu-item" onClick={onGitHubRepo}>
              <Github size={18} strokeWidth={2} color={COLORS.ink} />
              <span>Project</span>
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
          <button type="button" className="menu-item" onClick={onPreferences}>
            <Settings size={18} strokeWidth={2} color={COLORS.ink} />
            <span>Settings</span>
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

function PreferencesScreen(props: {
  preferences: AppPreferences;
  enabledModels: ModelOption[];
  onBack: () => void;
  onSave: (next: AppPreferences) => void;
  onOpenModelSettings: () => void;
}) {
  const { preferences, enabledModels, onBack, onSave, onOpenModelSettings } = props;

  // The screen edits a local draft so nothing persists until the user presses Save.
  const [fontSize, setFontSize] = useState<FontSizeId>(preferences.fontSize);
  const [defaultModelId, setDefaultModelId] = useState<string | null>(preferences.defaultModelId);
  const [maxToolCallsDraft, setMaxToolCallsDraft] = useState(
    preferences.maxToolCalls == null ? "" : String(preferences.maxToolCalls)
  );
  const [requestTimeoutDraft, setRequestTimeoutDraft] = useState(
    preferences.requestTimeoutSeconds == null ? "" : String(preferences.requestTimeoutSeconds)
  );
  const [justSaved, setJustSaved] = useState(false);

  // Re-seed the draft whenever the saved preferences change (e.g. after a save or an external update).
  useEffect(() => {
    setFontSize(preferences.fontSize);
    setDefaultModelId(preferences.defaultModelId);
    setMaxToolCallsDraft(preferences.maxToolCalls == null ? "" : String(preferences.maxToolCalls));
    setRequestTimeoutDraft(
      preferences.requestTimeoutSeconds == null ? "" : String(preferences.requestTimeoutSeconds)
    );
  }, [preferences]);

  const resolvedDefault = enabledModels.find((model) => model.id === defaultModelId)?.id ?? enabledModels[0]?.id ?? "";
  const normalizedMaxToolCalls = maxToolCallsDraft.trim() === "" ? null : normalizeMaxToolCalls(maxToolCallsDraft);
  const normalizedRequestTimeout = requestTimeoutDraft.trim() === "" ? null : normalizeRequestTimeout(requestTimeoutDraft);

  const dirty =
    fontSize !== preferences.fontSize
    || (resolvedDefault || null) !== preferences.defaultModelId
    || normalizedMaxToolCalls !== preferences.maxToolCalls
    || normalizedRequestTimeout !== preferences.requestTimeoutSeconds;

  const handleSave = () => {
    onSave({
      fontSize,
      defaultModelId: resolvedDefault || null,
      maxToolCalls: normalizedMaxToolCalls,
      requestTimeoutSeconds: normalizedRequestTimeout
    });
    setJustSaved(true);
    window.setTimeout(() => setJustSaved(false), 2000);
  };

  const maxToolCallsCurrent =
    preferences.maxToolCalls == null ? "Server default" : `${preferences.maxToolCalls} per message`;
  const requestTimeoutCurrent =
    preferences.requestTimeoutSeconds == null
      ? `Server default (${DEFAULT_REQUEST_TIMEOUT_SECONDS}s)`
      : `${preferences.requestTimeoutSeconds}s`;

  return (
    <>
      <div className="back-row">
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">Settings</h1>
        <p className="integration-subtitle">Personalize how Hugin looks and how the agent runs. Changes apply when you press Save.</p>
      </div>

      <div className="settings-section">
        <div className="history-group-label">FONT SIZE</div>
        <p className="settings-hint">Adjusts the text size across the entire app.</p>
        <div className="font-size-options" role="group" aria-label="Font size">
          {FONT_SIZE_OPTIONS.map((option) => (
            <button
              key={option.id}
              type="button"
              className={`font-size-option ${fontSize === option.id ? "font-size-option-active" : ""}`}
              aria-pressed={fontSize === option.id}
              onClick={() => setFontSize(option.id)}
            >
              <span className="font-size-preview" style={{ fontSize: `${option.scale}rem` }}>
                Aa
              </span>
              <span className="font-size-label">{option.label}</span>
            </button>
          ))}
        </div>
      </div>

      <div className="settings-section">
        <div className="history-group-label">DEFAULT MODEL</div>
        <p className="settings-hint">New chats start with this model. Choose from the models you have enabled.</p>
        {enabledModels.length === 0 ? (
          <p className="history-empty">
            No models are enabled yet.{" "}
            <button type="button" className="link-button" onClick={onOpenModelSettings}>
              Enable a model
            </button>{" "}
            to set a default.
          </p>
        ) : (
          <label className="composer-select settings-select">
            <span>Model</span>
            <select value={resolvedDefault} onChange={(event) => setDefaultModelId(event.target.value)}>
              {enabledModels.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.name}
                </option>
              ))}
            </select>
          </label>
        )}
        <button type="button" className="secondary-button settings-manage-button" onClick={onOpenModelSettings}>
          Manage models
        </button>
      </div>

      <div className="settings-section">
        <div className="history-group-label">MAX TOOL CALLS</div>
        <p className="settings-hint">
          Caps how many tool-call steps the agent may take to answer a single message. Leave blank to
          use the server default.
        </p>
        <p className="settings-current">Current: {maxToolCallsCurrent}</p>
        <label className="composer-select settings-select">
          <span>Limit per message</span>
          <input
            type="number"
            inputMode="numeric"
            min={MAX_TOOL_CALLS_MIN}
            max={MAX_TOOL_CALLS_MAX}
            value={maxToolCallsDraft}
            placeholder="Server default"
            onChange={(event) => setMaxToolCallsDraft(event.target.value)}
            className="settings-number-input"
          />
        </label>
      </div>

      <div className="settings-section">
        <div className="history-group-label">REQUEST TIMEOUT</div>
        <p className="settings-hint">
          How long (in seconds) the agent may work on a single message before timing out. Leave blank to
          use the server default. Allowed range: {REQUEST_TIMEOUT_MIN}–{REQUEST_TIMEOUT_MAX}s.
        </p>
        <p className="settings-current">Current: {requestTimeoutCurrent}</p>
        <label className="composer-select settings-select">
          <span>Seconds per message</span>
          <input
            type="number"
            inputMode="numeric"
            min={REQUEST_TIMEOUT_MIN}
            max={REQUEST_TIMEOUT_MAX}
            value={requestTimeoutDraft}
            placeholder="Server default"
            onChange={(event) => setRequestTimeoutDraft(event.target.value)}
            className="settings-number-input"
          />
        </label>
      </div>

      <div className="settings-section settings-actions">
        <button type="button" className="primary-button" onClick={handleSave} disabled={!dirty}>
          {justSaved ? "Saved" : "Save settings"}
        </button>
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

  const store = useChatSessionStore(session?.token ?? null);
  const thread = store.activeThread;

  const [draft, setDraft] = useState("");
  const [draftAttachment, setDraftAttachment] = useState<ChatAttachment | null>(null);
  const [workspaceBusy, setWorkspaceBusy] = useState(false);
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
  const [preferences, setPreferences] = useState<AppPreferences>(() => loadPreferences());

  const [historyQuery, setHistoryQuery] = useState("");
  const [deletingThreadId, setDeletingThreadId] = useState<string | null>(null);
  const [returnScreen, setReturnScreen] = useState<Screen>("purechat");

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loginError, setLoginError] = useState<string | null>(null);
  const [signingIn, setSigningIn] = useState(false);
  const integrationsVisibleRef = useRef(false);
  const bootstrappedRef = useRef(false);

  const listRef = useRef<HTMLDivElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);

  const busy = store.activeBusy || workspaceBusy;

  // Mirror the active sandbox id so file refreshes can fall back to it without depending on the
  // store object identity (which would otherwise re-run the file-loading effect every render).
  const activeSandboxRef = useRef<string | undefined>(thread?.sandboxId);
  activeSandboxRef.current = thread?.sandboxId;
  // Mirror the active thread kind so post-run file refreshes know whether to read the host (~/) tree
  // (Agent mode) or a sandbox tree (Project mode).
  const activeKindRef = useRef(thread?.kind);
  activeKindRef.current = thread?.kind;

  useEffect(() => {
    integrationsVisibleRef.current = screen === "integrations";
  }, [screen]);

  // Validate any stored session on load so a refresh keeps the user signed in and re-opens the
  // last active thread, rebuilt from backend events by the store.
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
        fetchGitHubStatus(validated.token).then((status) => setGitHubStatus(status)).catch(() => setGitHubStatus(null));
      })
      .catch(() => saveAuthSession(null))
      .finally(() => setBooting(false));
  }, []);

  // Once authenticated, activate a thread: the restored one from the local index, or a fresh chat.
  // Runs once per session; sign-in performs its own activation and sets the guard.
  useEffect(() => {
    if (!session || bootstrappedRef.current) return;
    bootstrappedRef.current = true;
    const restored = restorePreferredThread(store.threads);
    const launch = readLaunchScreen();
    if (restored) {
      store.switchThread(restored.thread);
      setScreen(launch === "integrations" ? "integrations" : restored.screen);
    } else {
      const fresh = createThread("chat");
      store.switchThread(fresh);
      setScreen(launch === "integrations" ? "integrations" : "purechat");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session]);

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

  const updatePreferences = useCallback((partial: Partial<AppPreferences>) => {
    setPreferences((current) => {
      const next = { ...current, ...partial };
      savePreferences(next);
      return next;
    });
  }, []);

  // Apply the saved font size to the document root so the whole app scales with the preference.
  useEffect(() => {
    applyFontSize(preferences.fontSize);
  }, [preferences.fontSize]);

  // Keep the default model pointing at an enabled model: when it gets disabled in Model settings,
  // fall through to the next enabled model in the list (and seed a default once models first load).
  useEffect(() => {
    if (!models.length) return;
    const resolved = resolveDefaultModelId(models, preferences.defaultModelId);
    if (resolved !== preferences.defaultModelId) {
      updatePreferences({ defaultModelId: resolved });
    }
  }, [models, preferences.defaultModelId, updatePreferences]);

  // Keep scrolled to the latest message as the transcript grows.
  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [thread?.entries]);

  // Default the active thread's model/reasoning to an enabled model once models load.
  useEffect(() => {
    if (!thread) return;
    const enabled = models.filter((model) => model.enabled);
    if (!enabled.length) return;
    const preferredDefault = enabled.find((model) => model.id === preferences.defaultModelId) ?? enabled[0];
    const selected = enabled.find((model) => model.id === thread.modelId) ?? preferredDefault;
    const nextReasoning = thread.reasoningEffort && selected.reasoningOptions.includes(thread.reasoningEffort)
      ? thread.reasoningEffort
      : defaultReasoningFor(selected);
    if (thread.modelId === selected.id && thread.reasoningEffort === nextReasoning) return;
    store.patchThread(thread.id, { modelId: selected.id, reasoningEffort: nextReasoning });
  }, [models, thread, store, preferences.defaultModelId]);

  const refreshFiles = useCallback(
    async (sandboxId?: string) => {
      const id = sandboxId ?? activeSandboxRef.current;
      if (!id || !session) return;
      try {
        setFiles(await fetchSandboxFiles(session.token, id));
      } catch {
        setFiles([]);
      }
    },
    [session]
  );

  // Loads the server home directory (~/) file tree that backs Agent-mode chats.
  const refreshAgentFiles = useCallback(async () => {
    if (!session) return;
    try {
      setFiles(await fetchAgentWorkspaceFiles(session.token));
    } catch {
      setFiles([]);
    }
  }, [session]);

  useEffect(() => {
    if (!session || screen !== "chat" || !thread) {
      setFiles([]);
      return;
    }
    setFiles([]);
    if (thread.kind === "agent") {
      void refreshAgentFiles();
    } else if (thread.sandboxId) {
      void refreshFiles(thread.sandboxId);
    }
  }, [session, screen, thread?.id, thread?.kind, thread?.sandboxId, refreshFiles, refreshAgentFiles]);

  // Refresh the workspace file tree whenever a run finishes (busy true -> false), so files the agent
  // created/edited during the run are reflected.
  const prevBusyRef = useRef(busy);
  useEffect(() => {
    const justFinished = prevBusyRef.current && !busy;
    prevBusyRef.current = busy;
    if (justFinished && screen === "chat") {
      if (activeKindRef.current === "agent") {
        void refreshAgentFiles();
      } else if (activeSandboxRef.current) {
        void refreshFiles(activeSandboxRef.current);
      }
    }
  }, [busy, screen, refreshFiles, refreshAgentFiles]);

  const signIn = useCallback(async () => {
    if (!username.trim() || !password.trim() || signingIn) return;
    setSigningIn(true);
    setLoginError(null);
    try {
      const validated = await loginRequest(username.trim(), password);
      saveAuthSession(validated);
      bootstrappedRef.current = true;
      setSession(validated);
      const fresh = createThread("chat");
      store.switchThread(fresh);
      setScreen(readLaunchScreen() === "integrations" ? "integrations" : "purechat");
      fetchModels(validated.token).then((next) => setModels(next)).catch(() => setModels([]));
      fetchGitHubStatus(validated.token).then((status) => setGitHubStatus(status)).catch(() => setGitHubStatus(null));
      setPassword("");
    } catch (e) {
      setLoginError(e instanceof Error ? e.message : "Sign in failed.");
    } finally {
      setSigningIn(false);
    }
  }, [username, password, signingIn, store]);

  const startChat = useCallback(() => {
    const fresh = createThread("chat");
    store.switchThread(fresh);
    setFiles([]);
    setWsOpen(true);
    setDraftAttachment(null);
    setBugReportNotice(null);
    setError(null);
    setHistoryQuery("");
    setScreen("purechat");
    setMenuOpen(false);
  }, [store]);

  const startAgent = useCallback(() => {
    if (!session) return;
    setMenuOpen(false);
    setHistoryQuery("");
    setBugReportNotice(null);
    setError(null);
    setFiles([]);
    setWsOpen(true);
    setDraftAttachment(null);
    // Agent mode runs against the server home directory (~/); no sandbox is provisioned. The thread
    // is created locally and the home file tree is loaded for the workspace panel.
    store.switchThread(createThread("agent"));
    setScreen("chat");
    void refreshAgentFiles();
  }, [session, store, refreshAgentFiles]);

  const openHistory = useCallback(
    (item: ChatThread) => {
      store.switchThread(item);
      setDraftAttachment(null);
      setBugReportNotice(null);
      setError(null);
      setMenuOpen(false);
      if (item.kind === "agent") {
        setScreen("chat");
        setFiles([]);
        setWsOpen(true);
        void refreshAgentFiles();
      } else if (item.kind === "github") {
        setScreen("chat");
        setFiles([]);
        setWsOpen(false);
        if (item.sandboxId) void refreshFiles(item.sandboxId);
      } else {
        setScreen("purechat");
      }
    },
    [store, refreshFiles, refreshAgentFiles]
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

        const wasActive = store.activeThreadId === item.id;
        store.removeThread(item.id);
        if (wasActive) {
          store.switchThread(createThread("chat"));
          setFiles([]);
          setScreen("purechat");
        }
      } catch (e) {
        setError(e instanceof Error ? e.message : "Could not delete this conversation.");
      } finally {
        setDeletingThreadId(null);
      }
    },
    [deletingThreadId, session, store]
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

  const openPreferences = useCallback(() => {
    setReturnScreen(screen === "preferences" || screen === "settings" ? returnScreen : screen);
    setScreen("preferences");
    setMenuOpen(false);
    setBugReportNotice(null);
  }, [screen, returnScreen]);

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
    [session, loadIntegrations, pollGoogleIntegrationRefresh]
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
    [session, loadIntegrations, pollGoogleIntegrationRefresh]
  );

  const send = useCallback(
    async (textArg?: string) => {
      const text = (textArg ?? draft).trim();
      const attachment = draftAttachment;
      const current = store.activeThread;
      if ((!text && !attachment) || busy || !session || !current) return;
      const enabledModels = models.filter((model) => model.enabled);
      const preferredDefault = enabledModels.find((model) => model.id === preferences.defaultModelId) ?? enabledModels[0];
      const selectedModel = enabledModels.find((model) => model.id === current.modelId) ?? preferredDefault;
      if (!selectedModel) {
        setError("Enable at least one model in Model settings before sending a message.");
        return;
      }
      const selectedReasoning = selectedModel.reasoningOptions.includes(current.reasoningEffort ?? "")
        ? current.reasoningEffort
        : defaultReasoningFor(selectedModel);

      const sandboxId = current.sandboxId;

      setDraft("");
      setDraftAttachment(null);
      setBugReportNotice(null);
      setError(null);

      const isFirst = !current.entries.some((entry) => entry.type === "user");
      const title = isFirst && current.kind !== "github"
        ? getThreadTitle(text || attachment?.name || "Image attachment")
        : current.title;
      store.patchThread(current.id, {
        modelId: selectedModel.id,
        reasoningEffort: selectedReasoning,
        title,
        updatedAt: nowIso()
      });

      const mode = current.kind === "github" ? "GITHUB" : current.kind === "agent" ? "AGENT" : "CHAT";

      try {
        await store.sendMessage(current.id, {
          content: text,
          mode,
          title,
          attachments: attachment ? [attachment] : undefined,
          model: selectedModel.id,
          reasoningEffort: selectedReasoning,
          sandboxId,
          maxToolCalls: preferences.maxToolCalls,
          requestTimeoutSeconds: preferences.requestTimeoutSeconds
        });
      } catch (e) {
        setError(e instanceof Error ? e.message : "The agent request failed.");
      } finally {
        if (current.kind === "agent") {
          void refreshAgentFiles();
        } else if (sandboxId) {
          void refreshFiles(sandboxId);
        }
      }
    },
    [draft, draftAttachment, busy, session, refreshFiles, refreshAgentFiles, models, store, preferences.defaultModelId, preferences.maxToolCalls, preferences.requestTimeoutSeconds]
  );

  useEffect(() => {
    if (!pendingAutoPrompt || busy) return;
    setPendingAutoPrompt(null);
    void send(pendingAutoPrompt);
  }, [pendingAutoPrompt, busy, send]);

  const saveBugReport = useCallback(async () => {
    if (!session || reportingBug) return;
    const activeThread = store.activeThread;
    if (!activeThread) return;
    setReportingBug(true);
    setError(null);
    setBugReportNotice(null);
    try {
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
  }, [session, reportingBug, screen, busy, store]);

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
    setWorkspaceBusy(true);
    setError(null);
    try {
      const sandbox = await createGitHubSandbox(session.token, selectedRepo, selectedBranch, selectedBugReportId || undefined);
      setFiles([]);
      setWsOpen(false);
      setDraft("");
      setDraftAttachment(null);
      store.switchThread(createThread("github", {
        sandboxId: sandbox.id,
        repoFullName: repo.fullName,
        repoName: repo.name,
        branchName: selectedBranch
      }));
      setScreen("chat");
      if (selectedBugReport) {
        setPendingAutoPrompt(buildGitHubBugReportPrompt(selectedBugReport));
      }
      void refreshFiles(sandbox.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start a GitHub repo sandbox.");
    } finally {
      setWorkspaceBusy(false);
    }
  }, [session, selectedRepo, selectedBranch, selectedBugReportId, repoOptions, bugReports, refreshFiles, store]);

  const enabledModels = models.filter((model) => model.enabled);
  const preferredDefaultModel = enabledModels.find((model) => model.id === preferences.defaultModelId) ?? enabledModels[0];
  const activeModel = enabledModels.find((model) => model.id === thread?.modelId) ?? preferredDefaultModel;
  const activeReasoning = activeModel?.reasoningOptions.includes(thread?.reasoningEffort ?? "")
    ? thread?.reasoningEffort
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
            {screen === "chat" && thread ? (
              <WorkspacePanel
                sessionId={thread.id}
                files={files}
                wsOpen={wsOpen}
                onToggleWs={() => setWsOpen((current) => !current)}
                label={thread.kind === "github"
                  ? `${thread.repoName ?? thread.repoFullName ?? "repo"} · ${thread.branchName ?? "branch"}`
                  : "~/"}
                rootName={thread.kind === "github"
                  ? (thread.repoName ?? thread.repoFullName ?? "repo")
                  : "~"}
                badge={thread.kind === "github" ? "github" : "agent"}
                defaultOpenDirectories={thread.kind !== "github"}
              />
            ) : null}
            {error ? <p className="login-error screen-pad">{error}</p> : null}
            {bugReportNotice ? <p className="screen-note screen-pad">{bugReportNotice}</p> : null}
            {thread ? (
              <ChatPanel
                name={name}
                entries={thread.entries}
                busy={busy}
                running={store.activeBusy}
                listRef={listRef}
                draft={draft}
                attachment={draftAttachment}
                models={enabledModels}
                selectedModelId={activeModel?.id}
                selectedReasoning={activeReasoning}
                onDraftChange={setDraft}
                onModelChange={(modelId) => {
                  const model = enabledModels.find((item) => item.id === modelId);
                  store.patchThread(thread.id, { modelId, reasoningEffort: defaultReasoningFor(model) });
                }}
                onReasoningChange={(reasoningEffort) => store.patchThread(thread.id, { reasoningEffort })}
                onPickImage={pickImage}
                onClearImage={clearImage}
                onSend={send}
                onStop={() => void store.cancelRun(thread.id)}
                onApproval={(approvalId, decision) => void store.resolveApproval(thread.id, approvalId, decision)}
              />
            ) : null}
          </>
        ) : screen === "integrations" ? (
          <IntegrationPanel
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
            busy={workspaceBusy}
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
        ) : screen === "preferences" ? (
          <PreferencesScreen
            preferences={preferences}
            enabledModels={enabledModels}
            onBack={() => setScreen(returnScreen)}
            onSave={(next) => updatePreferences(next)}
            onOpenModelSettings={openSettings}
          />
        ) : screen === "agent-threads" ? (
          <AgentThreadsScreen
            runs={agentRuns}
            threads={store.threads}
            busyRunId={agentRunBusyId}
            loading={agentRunsLoading}
            onBack={() => setScreen(returnScreen)}
            onCancel={cancelRun}
          />
        ) : (
          <HistoryPanel
            threads={store.historyThreads}
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
            onAgent={startAgent}
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
            onPreferences={openPreferences}
          />
        ) : null}
      </div>
    </div>
  );
}
