import { useCallback, useEffect, useRef, useState, type ReactNode, type RefObject } from "react";
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
  History,
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
  User,
  Wifi,
  X
} from "lucide-react";

import {
  buildAssistantEntry,
  buildUserEntry,
  connectGitHub,
  createSandbox,
  createThread,
  disconnectGitHub,
  disconnectGoogle,
  fetchCurrentUser,
  fetchIntegrations,
  fetchSandboxFiles,
  formatTimestamp,
  getThreadTitle,
  loadAppState,
  loadAuthSession,
  login as loginRequest,
  reconnectGoogle,
  saveAppState,
  saveAuthSession,
  streamPrompt,
  type StreamEvent
} from "./services/guildService";
import type {
  AppState,
  AuthSession,
  ChatEntry,
  ChatThread,
  FileNode,
  Integration
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

type Screen = "login" | "chat" | "purechat" | "history" | "integrations";

const MENU_ITEMS = [
  ["New chat", MessageCirclePlus, "chat"],
  ["New sandbox", Box, "sandbox"],
  ["History", History, "history"],
  ["Integrations", Puzzle, "integrations"]
] as const;

function entryId(prefix: string) {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
  }
  return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
}

function nowIso() {
  return new Date().toISOString();
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

/** Folds a streamed agent event into the working thread, keyed by the active assistant entry. */
function applyStreamEvent(thread: ChatThread, assistantId: string, event: StreamEvent): ChatThread {
  const entries = thread.entries.slice();
  const idx = entries.findIndex((entry) => entry.id === assistantId);
  if (idx === -1) return thread;
  const assistant = entries[idx] as Extract<ChatEntry, { type: "assistant" }>;

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
      // Insert tool activity just before the assistant bubble so the final answer stays last.
      entries.splice(idx, 0, toolEntry);
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
    default:
      break;
  }

  return { ...thread, updatedAt: nowIso(), entries };
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

function FileNodeRow({ node, depth }: { node: FileNode; depth: number }) {
  const [open, setOpen] = useState(true);

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
        {open ? node.children?.map((child) => <FileNodeRow key={child.path} node={child} depth={depth + 1} />) : null}
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
}) {
  const { sessionId, files, wsOpen, onToggleWs } = props;

  return (
    <div className="file-tree">
      <TreeRow>
        <ChevronDown size={13} color={COLORS.faint} />
        <Network size={14} strokeWidth={2} color={COLORS.ink} />
        <span className="mono">~/sandbox/{sessionId.slice(0, 8)}</span>
        <span className="tree-badge">sandbox</span>
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
          files.map((node) => <FileNodeRow key={node.path} node={node} depth={2} />)
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
              <div className="message-bubble message-bubble-user">{entry.content}</div>
            </div>
          );
        }

        if (entry.type === "tool") {
          return (
            <div key={entry.id} className="message-row message-row-assistant fade-in">
              <div className="assistant-event">
                {entry.tool.finishedAt ? (
                  <Check size={15} strokeWidth={3} color={COLORS.green} />
                ) : (
                  <TypingDots />
                )}
                <span>
                  Used <span className="mono">{entry.tool.name}</span>
                </span>
              </div>
            </div>
          );
        }

        const empty = !entry.content;
        return (
          <div key={entry.id} className="message-row message-row-assistant fade-in">
            <div className="message-bubble message-bubble-assistant">
              {empty && busy ? <TypingDots /> : <span>{entry.content}</span>}
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
  onChange: (value: string) => void;
  onSend: () => void;
}) {
  const { value, disabled, onChange, onSend } = props;

  return (
    <div className="input-wrap">
      <div className="input-bar">
        <input
          value={value}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !disabled) onSend();
          }}
          placeholder="Message Hugin…"
        />
        <button type="button" onClick={onSend} disabled={disabled || !value.trim()} aria-label="Send message">
          <Send size={19} strokeWidth={2} color={COLORS.ink} />
        </button>
      </div>
      <p className="input-note">Hugin can make mistakes. Please verify important information.</p>
    </div>
  );
}

function MenuOverlay(props: {
  username: string;
  roles: string[];
  onClose: () => void;
  onSandbox: () => void;
  onChat: () => void;
  onHistory: () => void;
  onIntegrations: () => void;
}) {
  const { username, roles, onClose, onSandbox, onChat, onHistory, onIntegrations } = props;
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
          {MENU_ITEMS.map(([label, Icon, action]) => {
            const handler =
              action === "sandbox"
                ? onSandbox
                : action === "chat"
                ? onChat
                : action === "history"
                ? onHistory
                : onIntegrations;
            return (
              <button key={label} type="button" className="menu-item" onClick={handler}>
                <Icon size={18} strokeWidth={2} color={COLORS.ink} />
                <span>{label}</span>
              </button>
            );
          })}
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
  const threadRef = useRef(thread);

  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [files, setFiles] = useState<FileNode[]>([]);
  const [wsOpen, setWsOpen] = useState(true);

  const [integrations, setIntegrations] = useState<Integration[]>([]);
  const [integrationBusy, setIntegrationBusy] = useState<string | null>(null);

  const [historyQuery, setHistoryQuery] = useState("");
  const [returnScreen, setReturnScreen] = useState<Screen>("purechat");

  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loginError, setLoginError] = useState<string | null>(null);
  const [signingIn, setSigningIn] = useState(false);

  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    threadRef.current = thread;
  }, [thread]);

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
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [thread.entries]);

  // Persist a thread to history only once the user has actually sent a message.
  useEffect(() => {
    if (!thread.entries.some((entry) => entry.type === "user")) return;
    setState((prev) => {
      const exists = prev.threads.some((existing) => existing.id === thread.id);
      const threads = exists
        ? prev.threads.map((existing) => (existing.id === thread.id ? thread : existing))
        : [thread, ...prev.threads];
      const next = { ...prev, threads };
      saveAppState(next);
      return next;
    });
  }, [thread]);

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
    setBusy(true);
    try {
      const sandbox = await createSandbox(session.token);
      setThread(createThread("sandbox", sandbox.id));
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
      setError(null);
      setMenuOpen(false);
      if (item.kind === "sandbox") {
        setScreen("chat");
        setFiles([]);
        if (item.sandboxId) void refreshFiles(item.sandboxId);
      } else {
        setScreen("purechat");
      }
    },
    [refreshFiles]
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
            const installUrl = await connectGitHub(session.token, window.location.href);
            if (installUrl) {
              window.location.assign(installUrl);
              return;
            }
          }
        }
        setIntegrations(await fetchIntegrations(session.token));
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
      if (!text || busy || !session) return;

      setDraft("");
      setBusy(true);
      setError(null);

      const userEntry = buildUserEntry(text);
      const assistant = buildAssistantEntry();
      setThread((current) => {
        const isFirst = !current.entries.some((entry) => entry.type === "user");
        return {
          ...current,
          title: isFirst ? getThreadTitle(text) : current.title,
          updatedAt: nowIso(),
          entries: [...current.entries, userEntry, assistant]
        };
      });

      try {
        await streamPrompt(
          session.token,
          { threadId: threadRef.current.id, prompt: text, sandboxId: threadRef.current.sandboxId },
          { onEvent: (event) => setThread((current) => applyStreamEvent(current, assistant.id, event)) }
        );
      } catch (e) {
        const message = e instanceof Error ? e.message : "The agent request failed.";
        setThread((current) => applyStreamEvent(current, assistant.id, { type: "error", message }));
      } finally {
        setBusy(false);
        if (threadRef.current.sandboxId) void refreshFiles();
      }
    },
    [draft, busy, session, refreshFiles]
  );

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
            {screen === "chat" ? (
              <FileTree
                sessionId={thread.id}
                files={files}
                wsOpen={wsOpen}
                onToggleWs={() => setWsOpen((current) => !current)}
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
            <InputBar value={draft} onChange={setDraft} onSend={() => send()} disabled={busy} />
          </>
        ) : screen === "integrations" ? (
          <IntegrationsScreen
            integrations={integrations}
            busyId={integrationBusy}
            onBack={() => setScreen(returnScreen)}
            onToggle={toggleIntegration}
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
            onClose={() => setMenuOpen(false)}
            onSandbox={startSandbox}
            onChat={startChat}
            onHistory={() => {
              setHistoryQuery("");
              setScreen("history");
              setMenuOpen(false);
            }}
            onIntegrations={openIntegrations}
          />
        ) : null}
      </div>
    </div>
  );
}
