import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  ArrowRight,
  Bot,
  Check,
  ChevronDown,
  ChevronRight,
  CircleCheckBig,
  Database,
  Gauge,
  Globe,
  LoaderCircle,
  MessageSquare,
  LogOut,
  Plus,
  RefreshCw,
  Settings2,
  ShieldCheck,
  Sparkles,
  StopCircle,
  Terminal,
  Trash2,
  Wrench,
  X
} from "lucide-react";

type TabId = "chat" | "agents" | "settings";
type Role = "user" | "assistant" | "system";
type AuthState = "loading" | "signed_out" | "signed_in";

type ChatMessage = {
  id: string;
  role: Role;
  content: string;
  reasoning?: string;
  tools?: ToolEvent[];
  done?: boolean;
  error?: boolean;
};

type ToolEvent = {
  name: string;
  args: string;
  result?: string;
  state: "running" | "done";
};

type ChatSession = {
  id: string;
  title: string;
  createdAt: number;
  messages: ChatMessage[];
};

type AppSettings = {
  baseUrl: string;
  decision: string;
  complex: string;
  simple: string;
  model?: string;
};

type AuthProfile = {
  baseUrl: string;
  token: string;
  username: string;
  expiresAt: string;
};

type LoginResponse = {
  token: string;
  tokenType: string;
  expiresAt: string;
  username: string;
  roles: string[];
};

type AuthMeResponse = {
  username: string;
  roles: string[];
  issuedAt?: string;
  expiresAt?: string;
};

type AgentStreamEvent = {
  type: string;
  data: {
    text?: string;
    name?: string;
    args?: string;
    result?: string;
    message?: string;
    [key: string]: unknown;
  };
};

type ToolSummary = {
  name: string;
  description: string;
  server: string;
  transport: string;
};

type UserAgent = {
  id: string;
  name: string;
  purpose: string;
  systemPrompt: string;
  model?: string | null;
  createdAt: string;
  updatedAt: string;
};

function buildUrl(baseUrl: string, path: string) {
  const base = baseUrl.replace(/\/+$/, "");
  return `${base}${path}`;
}

async function readErrorMessage(response: Response) {
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    const payload = (await response.json()) as { error?: string; message?: string };
    return payload.error || payload.message || `${response.status} ${response.statusText}`;
  }
  const text = await response.text();
  return text || `${response.status} ${response.statusText}`;
}

const STORAGE_KEYS = {
  settings: "hugin-web-settings",
  auth: "hugin-web-auth"
} as const;

const DEFAULT_BASE_URL = import.meta.env.VITE_HUGIN_BACKEND_URL?.trim() || "http://localhost:8080";

const DEFAULT_SETTINGS: AppSettings = {
  baseUrl: DEFAULT_BASE_URL,
  decision: "llama3.2",
  complex: "openai/gpt-oss-120b",
  simple: "openai/gpt-oss-20b"
};

const QUICK_PROMPTS = [
  "What time is it in Tokyo right now?",
  "Summarize the tools Hugin has available.",
  "What local tools can Hugin use?",
  "What can this app do?"
];

const TOOL_ICONS: Record<string, typeof Wrench> = {
  web_search: Globe,
  get_current_time: Gauge,
  google_sheets_read: Database,
  google_sheets_append: Database,
  google_docs_read: Database,
  read_file: Wrench,
  edit_file: Wrench,
  run_command: Terminal
};

function uid(prefix = "id") {
  if (crypto.randomUUID) return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
  return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
}

function safeJsonParse<T>(value: string, fallback: T): T {
  try {
    return JSON.parse(value) as T;
  } catch {
    return fallback;
  }
}

function formatRelativeTime(iso: string) {
  const date = new Date(iso);
  const delta = date.getTime() - Date.now();
  const abs = Math.abs(delta);
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ["day", 24 * 60 * 60 * 1000],
    ["hour", 60 * 60 * 1000],
    ["minute", 60 * 1000],
    ["second", 1000]
  ];
  const rtf = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  for (const [unit, size] of units) {
    if (abs >= size || unit === "second") {
      return rtf.format(Math.round(delta / size), unit);
    }
  }
  return rtf.format(0, "second");
}

async function readSse(
  response: Response,
  onEvent: (event: AgentStreamEvent) => void
): Promise<void> {
  if (!response.body) {
    throw new Error("Empty SSE response body");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });

    let boundary = buffer.indexOf("\n\n");
    while (boundary !== -1) {
      const chunk = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      const event = parseSseBlock(chunk);
      if (event) onEvent(event);
      boundary = buffer.indexOf("\n\n");
    }
  }

  const tail = buffer.trim();
  if (tail) {
    const event = parseSseBlock(tail);
    if (event) onEvent(event);
  }
}

function parseSseBlock(block: string): AgentStreamEvent | null {
  let eventName = "";
  const dataLines: string[] = [];

  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trim());
    }
  }

  if (!eventName) return null;

  const raw = dataLines.join("\n").trim();
  const data = raw ? safeJsonParse<Record<string, unknown>>(raw, {}) : {};
  return { type: eventName, data };
}

function loadSettings(): AppSettings {
  const stored = localStorage.getItem(STORAGE_KEYS.settings);
  if (!stored) return DEFAULT_SETTINGS;
  const parsed = safeJsonParse<Partial<AppSettings> & { model?: string }>(stored, {});
  const legacyModel = parsed.model || "";
  return {
    ...DEFAULT_SETTINGS,
    ...parsed,
    decision: parsed.decision || legacyModel || DEFAULT_SETTINGS.decision,
    complex: parsed.complex || legacyModel || DEFAULT_SETTINGS.complex,
    simple: parsed.simple || legacyModel || DEFAULT_SETTINGS.simple
  };
}

function loadAuth(): AuthProfile | null {
  const stored = localStorage.getItem(STORAGE_KEYS.auth);
  if (!stored) return null;
  return safeJsonParse<AuthProfile | null>(stored, null);
}

function sessionStorageKey(username: string) {
  return `hugin-web-sessions:${username}`;
}

function sessionStorageKeyForAgent(username: string, agentId: string | null) {
  return `${sessionStorageKey(username)}:${agentId || "default"}`;
}

function activeSessionStorageKey(username: string, agentId: string | null) {
  return `hugin-web-active-session:${username}:${agentId || "default"}`;
}

function defaultSessions(): ChatSession[] {
  return [
    {
      id: uid("session"),
      title: "General assistant",
      createdAt: Date.now(),
      messages: [
        {
          id: uid("msg"),
          role: "assistant",
          content:
            "Online and connected. I can stream chat responses, inspect local tools, and manage live settings."
        }
      ]
    }
  ];
}

function loadSessions(username: string | null | undefined, agentId: string | null | undefined): ChatSession[] {
  if (!username) return defaultSessions();
  const stored = localStorage.getItem(sessionStorageKeyForAgent(username, agentId || null));
  if (!stored) {
    return defaultSessions();
  }

  const sessions = safeJsonParse<ChatSession[]>(stored, []);
  return sessions.length > 0 ? sessions : defaultSessions();
}

function loadActiveSessionId(
  username: string | null | undefined,
  agentId: string | null | undefined,
  fallbackSessionId: string
) {
  if (!username) return fallbackSessionId;
  return localStorage.getItem(activeSessionStorageKey(username, agentId || null)) || fallbackSessionId;
}

function saveUserWorkspace(
  username: string | null | undefined,
  agentId: string | null | undefined,
  sessions: ChatSession[],
  activeSessionId: string
) {
  if (!username) return;
  localStorage.setItem(sessionStorageKeyForAgent(username, agentId || null), JSON.stringify(sessions));
  localStorage.setItem(activeSessionStorageKey(username, agentId || null), activeSessionId);
}

const DEFAULT_WORKSPACE = defaultSessions();

function App() {
  const [tab, setTab] = useState<TabId>("chat");
  const [settings, setSettings] = useState<AppSettings>(() => loadSettings());
  const [authState, setAuthState] = useState<AuthState>("loading");
  const [authProfile, setAuthProfile] = useState<AuthProfile | null>(() => loadAuth());
  const [authMessage, setAuthMessage] = useState<string>("Sign in to continue");
  const [loginUsername, setLoginUsername] = useState("test");
  const [loginPassword, setLoginPassword] = useState("password");
  const [loginBusy, setLoginBusy] = useState(false);
  const [loginError, setLoginError] = useState<string>("");
  const [sessions, setSessions] = useState<ChatSession[]>(() => DEFAULT_WORKSPACE);
  const [activeSessionId, setActiveSessionId] = useState<string>(() => DEFAULT_WORKSPACE[0]?.id || uid("session"));
  const [agents, setAgents] = useState<UserAgent[]>([]);
  const [selectedAgentId, setSelectedAgentId] = useState<string | null>(null);
  const [agentDraftName, setAgentDraftName] = useState("");
  const [agentDraftPurpose, setAgentDraftPurpose] = useState("");
  const [agentDraftModel, setAgentDraftModel] = useState("");
  const [agentActionBusy, setAgentActionBusy] = useState(false);
  const [tools, setTools] = useState<ToolSummary[]>([]);
  const [health, setHealth] = useState<"idle" | "checking" | "healthy" | "offline" | "error">(
    "idle"
  );
  const [healthMessage, setHealthMessage] = useState<string>("Ready");

  useEffect(() => {
    localStorage.setItem(STORAGE_KEYS.settings, JSON.stringify(settings));
  }, [settings]);

  useEffect(() => {
    if (authState === "signed_in" && authProfile?.username) {
      saveUserWorkspace(authProfile.username, selectedAgentId, sessions, activeSessionId);
    }
  }, [sessions, activeSessionId, authState, authProfile?.username, selectedAgentId]);

  useEffect(() => {
    if (authProfile) {
      localStorage.setItem(STORAGE_KEYS.auth, JSON.stringify(authProfile));
    } else {
      localStorage.removeItem(STORAGE_KEYS.auth);
    }
  }, [authProfile]);

  const activeSession = useMemo(
    () => sessions.find((session) => session.id === activeSessionId) ?? sessions[0],
    [activeSessionId, sessions]
  );

  const expireAuth = useCallback((message?: string) => {
    setAuthProfile(null);
    setAuthState("signed_out");
    setAuthMessage(message || "Sign in to continue");
    localStorage.removeItem(STORAGE_KEYS.auth);
    setSessions(DEFAULT_WORKSPACE);
    setActiveSessionId(DEFAULT_WORKSPACE[0]?.id || uid("session"));
    setAgents([]);
    setSelectedAgentId(null);
  }, []);

  const requestJson = useCallback(async <T,>(
    path: string,
    options?: RequestInit & { auth?: string | false }
  ): Promise<T> => {
    const response = await fetch(buildUrl(settings.baseUrl, path), {
      ...options,
      headers: {
        Accept: "application/json",
        ...(options?.headers ?? {}),
        ...(options?.auth
          ? { Authorization: `Bearer ${options.auth}` }
          : options?.auth === false
            ? {}
            : {})
      }
    });

    if (response.status === 401 && options?.auth !== false) {
      expireAuth("Session expired");
    }

    if (!response.ok) {
      throw new Error(await readErrorMessage(response));
    }
    return (await response.json()) as T;
  }, [expireAuth, settings.baseUrl]);

  const bootstrapAuth = useCallback(async () => {
    const stored = loadAuth();
    if (!stored || stored.baseUrl !== settings.baseUrl) {
      expireAuth();
      return;
    }

    setAuthState("loading");
    try {
      const me = await requestJson<AuthMeResponse>("/api/auth/me", {
        auth: stored.token
      });
      setAuthProfile(stored);
      const loadedAgents = await requestJson<UserAgent[]>("/api/agent/agents", {
        auth: stored.token
      });
      setAgents(loadedAgents);
      const initialAgentId = loadedAgents[0]?.id || null;
      setSelectedAgentId(initialAgentId);
      const loadedSessions = loadSessions(me.username, initialAgentId);
      setSessions(loadedSessions);
      setActiveSessionId(loadActiveSessionId(me.username, initialAgentId, loadedSessions[0]?.id || uid("session")));
      setAuthState("signed_in");
      setAuthMessage(`Signed in as ${me.username}`);
    } catch (error) {
      expireAuth(error instanceof Error ? error.message : "Session expired");
    }
  }, [expireAuth, requestJson, settings.baseUrl]);

  const refreshMetadata = useCallback(async (authToken = authProfile?.token) => {
    setHealth("checking");
    try {
      const toolsResponse = await requestJson<ToolSummary[]>(`/api/agent/tools`, {
        auth: authToken ?? false
      });
      setTools(toolsResponse);
      setHealth("healthy");
      setHealthMessage(`${toolsResponse.length} tools ready`);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Unable to reach backend";
      setHealthMessage(message);
      setHealth(locationLikelyLocalhost() ? "offline" : "error");
    }
  }, [authProfile?.token, requestJson]);

  const refreshAgents = useCallback(async (authToken = authProfile?.token) => {
    try {
      const data = await requestJson<UserAgent[]>("/api/agent/agents", {
        auth: authToken ?? false
      });
      setAgents(data);
      if (!data.find((agent) => agent.id === selectedAgentId)) {
        const nextAgentId = data[0]?.id || null;
        setSelectedAgentId(nextAgentId);
        const loadedSessions = loadSessions(authProfile?.username || null, nextAgentId);
        setSessions(loadedSessions);
        setActiveSessionId(
          loadActiveSessionId(authProfile?.username || null, nextAgentId, loadedSessions[0]?.id || uid("session"))
        );
      }
    } catch (error) {
      setHealthMessage(error instanceof Error ? error.message : "Failed to refresh agents");
    }
  }, [authProfile?.token, authProfile?.username, requestJson, selectedAgentId]);
  useEffect(() => {
    void bootstrapAuth();
  }, [bootstrapAuth]);

  useEffect(() => {
    if (authState === "signed_in") {
      void refreshMetadata();
    }
  }, [authState, refreshMetadata]);

  async function fetchJson<T>(
    path: string,
    init?: RequestInit,
    auth: string | false = authProfile?.token ?? false
  ): Promise<T> {
    return requestJson<T>(path, { ...init, auth });
  }

  function locationLikelyLocalhost() {
    return /localhost|127\.0\.0\.1|::1/.test(window.location.hostname);
  }

  function clearAuth(message?: string) {
    if (authProfile?.username) {
      saveUserWorkspace(authProfile.username, selectedAgentId, sessions, activeSessionId);
    }
    setAuthProfile(null);
    setAuthState("signed_out");
    setAuthMessage(message || "Sign in to continue");
    localStorage.removeItem(STORAGE_KEYS.auth);
    setSessions(DEFAULT_WORKSPACE);
    setActiveSessionId(DEFAULT_WORKSPACE[0]?.id || uid("session"));
    setAgents([]);
    setSelectedAgentId(null);
  }

  async function handleLogin(event: FormEvent) {
    event.preventDefault();
    setLoginBusy(true);
    setLoginError("");
    try {
      const response = await requestJson<LoginResponse>("/api/auth/login", {
        method: "POST",
        auth: false,
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          username: loginUsername,
          password: loginPassword
        })
      });

      const profile: AuthProfile = {
        baseUrl: settings.baseUrl,
        token: response.token,
        username: response.username,
        expiresAt: response.expiresAt
      };
      setAuthProfile(profile);
      const loadedAgents = await fetchJson<UserAgent[]>("/api/agent/agents", undefined, response.token);
      setAgents(loadedAgents);
      const initialAgentId = loadedAgents[0]?.id || null;
      setSelectedAgentId(initialAgentId);
      const loadedSessions = loadSessions(response.username, initialAgentId);
      setSessions(loadedSessions);
      setActiveSessionId(loadActiveSessionId(response.username, initialAgentId, loadedSessions[0]?.id || uid("session")));
      setAuthState("signed_in");
      setAuthMessage(`Signed in as ${response.username}`);
      setLoginPassword("");
      setLoginError("");
      await refreshMetadata(response.token);
    } catch (error) {
      setLoginError(error instanceof Error ? error.message : "Unable to sign in");
      setAuthState("signed_out");
    } finally {
      setLoginBusy(false);
    }
  }

  function handleLogout() {
    clearAuth("Signed out");
  }

  function handleSettingsChange(next: AppSettings) {
    if (next.baseUrl !== settings.baseUrl && authProfile) {
      clearAuth("Backend changed, sign in again");
    }
    setSettings(next);
  }

  function createSession() {
    const session: ChatSession = {
      id: uid("session"),
      title: "New conversation",
      createdAt: Date.now(),
      messages: [
        {
          id: uid("msg"),
          role: "assistant",
          content:
            "New session ready. Ask me something and I’ll stream the response from the Hugin backend."
        }
      ]
    };
    setSessions((current) => [session, ...current]);
    setActiveSessionId(session.id);
    setTab("chat");
  }

  async function sendPrompt(prompt: string, sessionId = activeSessionId) {
    const trimmed = prompt.trim();
    if (!trimmed) return;

    const session = sessions.find((item) => item.id === sessionId);
    if (!session) return;

    const userMessage: ChatMessage = {
      id: uid("msg"),
      role: "user",
      content: trimmed
    };
    const assistantMessage: ChatMessage = {
      id: uid("msg"),
      role: "assistant",
      content: "",
      tools: [],
      done: false
    };

    setSessions((current) =>
      current.map((item) =>
        item.id === sessionId
          ? {
              ...item,
              title: item.title === "New conversation" ? summarizePrompt(trimmed) : item.title,
              messages: [...item.messages, userMessage, assistantMessage]
            }
          : item
      )
    );

    try {
      await streamPrompt(sessionId, trimmed, assistantMessage.id);
    } catch (error) {
      const message = error instanceof Error ? error.message : "Request failed";
      setSessions((current) =>
        current.map((item) =>
          item.id === sessionId
            ? {
                ...item,
                messages: item.messages.map((m) =>
                  m.id === assistantMessage.id
                    ? { ...m, content: `Request failed: ${message}`, error: true, done: true }
                    : m
                )
              }
            : item
        )
      );
    }
  }

  async function streamPrompt(sessionId: string, prompt: string, assistantId: string) {
    const controller = new AbortController();
    const response = await fetch(buildUrl(settings.baseUrl, "/api/agent/stream"), {
      method: "POST",
      signal: controller.signal,
      headers: {
        "Content-Type": "application/json",
        Accept: "text/event-stream",
        ...(authProfile?.token ? { Authorization: `Bearer ${authProfile.token}` } : {})
      },
      body: JSON.stringify({
        prompt,
        agentId: selectedAgentId || undefined,
        ...(settings.decision.trim() && settings.complex.trim() && settings.simple.trim()
          ? {
              decision: settings.decision,
              complex: settings.complex,
              simple: settings.simple
            }
          : {
              model: settings.model || undefined
            }),
        sessionId
      })
    });

    if (!response.ok) {
      if (response.status === 401) {
        clearAuth("Session expired");
      }
      throw new Error(await readErrorMessage(response));
    }

    await readSse(response, (event) => {
      applyStreamEvent(sessionId, assistantId, event);
    });
  }

  function applyStreamEvent(sessionId: string, assistantId: string, event: AgentStreamEvent) {
    setSessions((current) =>
      current.map((session) => {
        if (session.id !== sessionId) return session;
        return {
          ...session,
          messages: session.messages.map((message) => {
            if (message.id !== assistantId) return message;

            if (event.type === "reasoning") {
              return {
                ...message,
                reasoning: `${message.reasoning || ""}${event.data.text}`
              };
            }

            if (event.type === "token") {
              return {
                ...message,
                content: `${message.content}${event.data.text}`
              };
            }

            if (event.type === "tool") {
              if (!event.data.name || !event.data.args) return message;
              return {
                ...message,
                tools: [
                  ...(message.tools || []),
                  { name: event.data.name, args: event.data.args, state: "running" }
                ]
              };
            }

            if (event.type === "tool_result") {
              if (!event.data.name || !event.data.result) return message;
              return {
                ...message,
                tools: (message.tools || []).map((tool) =>
                  tool.name === event.data.name ? { ...tool, result: event.data.result, state: "done" } : tool
                )
              };
            }

            if (event.type === "error") {
              return {
                ...message,
                content: `Request failed: ${event.data.message}`,
                error: true,
                done: true
              };
            }

            if (event.type === "done") {
              return { ...message, done: true };
            }

            return message;
          })
        };
      })
    );
  }

  async function sendPromptWithValue(value: string) {
    await sendPrompt(value);
  }

  async function refreshTools() {
    try {
      const data = await fetchJson<ToolSummary[]>("/api/agent/tools");
      setTools(data);
    } catch (error) {
      setHealthMessage(error instanceof Error ? error.message : "Failed to refresh tools");
    }
  }

  async function refreshAgentList() {
    await refreshAgents();
  }

  function selectAgent(agentId: string | null) {
    if (!authProfile?.username) {
      setSelectedAgentId(agentId);
      return;
    }

    saveUserWorkspace(authProfile.username, selectedAgentId, sessions, activeSessionId);
    setSelectedAgentId(agentId);
    const loadedSessions = loadSessions(authProfile.username, agentId);
    setSessions(loadedSessions);
    setActiveSessionId(loadActiveSessionId(authProfile.username, agentId, loadedSessions[0]?.id || uid("session")));
  }

  async function createAgent() {
    const name = agentDraftName.trim();
    const purpose = agentDraftPurpose.trim();
    if (!name || !purpose) return;

    setAgentActionBusy(true);
    try {
      const created = await fetchJson<UserAgent>("/api/agent/agents", {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          name,
          purpose,
          model: agentDraftModel.trim() || undefined
        })
      });
      setAgentDraftName("");
      setAgentDraftPurpose("");
      setAgentDraftModel("");
      await refreshAgentList();
      selectAgent(created.id);
      setTab("chat");
    } catch (error) {
      setHealthMessage(error instanceof Error ? error.message : "Could not create agent");
    } finally {
      setAgentActionBusy(false);
    }
  }

  async function deleteAgent(id: string) {
    setAgentActionBusy(true);
    try {
      await fetchJson<{ ok: boolean }>(`/api/agent/agents/${encodeURIComponent(id)}`, {
        method: "DELETE"
      });
      if (selectedAgentId === id) {
        const nextAgentId = agents.find((agent) => agent.id !== id)?.id || null;
        selectAgent(nextAgentId);
        if (!nextAgentId) {
          setTab("agents");
        }
      }
      await refreshAgentList();
    } catch (error) {
      setHealthMessage(error instanceof Error ? error.message : "Could not delete agent");
    } finally {
      setAgentActionBusy(false);
    }
  }

  const toolCount = tools.length;

  if (authState !== "signed_in") {
    return (
      <LoginScreen
        username={loginUsername}
        password={loginPassword}
        onChangeUsername={setLoginUsername}
        onChangePassword={setLoginPassword}
        onSubmit={handleLogin}
        busy={loginBusy}
        message={authMessage}
        error={loginError}
        status={authState}
      />
    );
  }

  return (
    <div className="app-shell">
      <div className="app-grid">
        <aside className="sidebar">
          <div className="brand-card">
            <div className="brand-mark">
              <Sparkles size={18} />
            </div>
            <div>
              <div className="eyebrow">Hugin</div>
              <h1>Olympus Atelier</h1>
            </div>
          </div>

          <div className={`status-card status-${health}`}>
            <div className="status-row">
              <span className="status-indicator" />
              <div>
                <div className="status-title">
                  {health === "healthy" ? "Connected" : health === "checking" ? "Checking" : "Disconnected"}
                </div>
                <div className="status-subtitle">{healthMessage}</div>
              </div>
            </div>
            <div className="sidebar-actions">
              <button className="ghost-button" onClick={() => void refreshMetadata()}>
                <RefreshCw size={14} />
                Refresh
              </button>
              <button className="ghost-button" onClick={handleLogout}>
                <LogOut size={14} />
                Logout
              </button>
            </div>
          </div>

          <div className="sidebar-panel">
            <div className="panel-header">
              <span>Agents</span>
              <button className="icon-button" onClick={() => setTab("agents")} title="Manage agents">
                <Plus size={15} />
              </button>
            </div>
            <div className="session-list">
              {agents.length ? (
                agents.map((agent) => (
                  <button
                    key={agent.id}
                    className={`session-item ${agent.id === selectedAgentId ? "active" : ""}`}
                    onClick={() => {
                      selectAgent(agent.id);
                      setTab("chat");
                    }}
                  >
                    <div className="session-title">{agent.name}</div>
                    <div className="session-meta">{agent.purpose}</div>
                  </button>
                ))
              ) : (
                <div className="empty-state compact">
                  No agents yet. Create one in the Agents tab to personalize chat.
                </div>
              )}
            </div>
          </div>

          <div className="sidebar-panel">
            <div className="panel-header">
              <span>Conversations</span>
              <button className="icon-button" onClick={createSession} title="New conversation">
                <Plus size={15} />
              </button>
            </div>
            <div className="session-list">
              {sessions.map((session) => (
                <button
                  key={session.id}
                  className={`session-item ${session.id === activeSessionId ? "active" : ""}`}
                  onClick={() => {
                    setActiveSessionId(session.id);
                    setTab("chat");
                  }}
                >
                  <div className="session-title">{session.title}</div>
                  <div className="session-meta">{session.messages.length - 1} messages</div>
                </button>
              ))}
            </div>
          </div>

          <div className="sidebar-panel">
            <div className="panel-header">
              <span>Endpoints</span>
              <button className="ghost-button compact" onClick={() => void refreshTools()}>
                <LoaderCircle size={14} />
                Sync
              </button>
            </div>
            <div className="endpoint-list">
              <EndpointRow label="/api/agent/tools" value={`${toolCount} tools`} icon={Wrench} />
              <EndpointRow label="/api/agent/agents" value={`${agents.length} agents`} icon={Bot} />
              <EndpointRow label="/api/agent/stream" value="Streaming chat" icon={MessageSquare} />
            </div>
          </div>

          <nav className="tab-nav">
            <TabButton current={tab} id="chat" label="Chat" icon={MessageSquare} onClick={setTab} />
            <TabButton current={tab} id="agents" label="Agents" icon={Bot} onClick={setTab} />
            <TabButton current={tab} id="settings" label="Settings" icon={Settings2} onClick={setTab} />
          </nav>
        </aside>

        <main className="content">
          <header className="topbar">
            <div>
              <div className="eyebrow">Olympian overview</div>
              <h2>
                {tab === "chat" && "Audience with Hugin"}
                {tab === "agents" && "Agent council"}
                {tab === "settings" && "Connection settings"}
              </h2>
            </div>
            <div className="topbar-meta">
              <Badge
                tone="slate"
                label={authProfile?.username ? `Signed in as ${authProfile.username}` : "signed in"}
              />
              <Badge
                tone={health === "healthy" ? "green" : health === "checking" ? "amber" : "slate"}
                label={health === "healthy" ? "Live" : health === "checking" ? "Syncing" : "Offline"}
              />
              <Badge
                tone="teal"
                label={
                  settings.decision.trim() && settings.complex.trim() && settings.simple.trim()
                    ? `Routing · ${settings.decision}`
                    : settings.model || "default model"
                }
              />
            </div>
          </header>

          {tab === "chat" && (
            <ChatPanel
              session={activeSession}
              onPrompt={sendPromptWithValue}
              onSettings={() => setTab("settings")}
              onNewSession={createSession}
              settings={settings}
              tools={tools}
              selectedAgent={agents.find((agent) => agent.id === selectedAgentId) || null}
              hasAgents={agents.length > 0}
              onOpenAgents={() => setTab("agents")}
            />
          )}

          {tab === "agents" && (
            <AgentsPanel
              agents={agents}
              selectedAgentId={selectedAgentId}
              onSelectAgent={selectAgent}
              onCreateAgent={createAgent}
              onDeleteAgent={deleteAgent}
              busy={agentActionBusy}
              draftName={agentDraftName}
              draftPurpose={agentDraftPurpose}
              draftModel={agentDraftModel}
              onChangeDraftName={setAgentDraftName}
              onChangeDraftPurpose={setAgentDraftPurpose}
              onChangeDraftModel={setAgentDraftModel}
              onOpenChat={() => setTab("chat")}
            />
          )}

          {tab === "settings" && (
            <SettingsPanel
              settings={settings}
              onChange={handleSettingsChange}
              onRefresh={refreshMetadata}
              onRefreshTools={refreshTools}
              tools={tools}
              username={authProfile?.username || ""}
            />
          )}
        </main>
      </div>
    </div>
  );
}

function summarizePrompt(prompt: string) {
  const trimmed = prompt.trim();
  if (trimmed.length <= 36) return trimmed;
  return `${trimmed.slice(0, 33).trimEnd()}…`;
}

function TabButton({
  current,
  id,
  label,
  icon: Icon,
  onClick
}: {
  current: TabId;
  id: TabId;
  label: string;
  icon: typeof MessageSquare;
  onClick: (tab: TabId) => void;
}) {
  return (
    <button className={`tab-button ${current === id ? "active" : ""}`} onClick={() => onClick(id)}>
      <Icon size={18} />
      <span>{label}</span>
    </button>
  );
}

function Badge({ tone, label }: { tone: "green" | "amber" | "slate" | "teal"; label: string }) {
  return <span className={`badge badge-${tone}`}>{label}</span>;
}

function EndpointRow({
  label,
  value,
  icon: Icon
}: {
  label: string;
  value: string;
  icon: typeof Wrench;
}) {
  return (
    <div className="endpoint-row">
      <div className="endpoint-label">
        <Icon size={14} />
        {label}
      </div>
      <div className="endpoint-value">{value}</div>
    </div>
  );
}

function ChatPanel({
  session,
  onPrompt,
  onSettings,
  onNewSession,
  onOpenAgents,
  settings,
  tools,
  selectedAgent,
  hasAgents
}: {
  session?: ChatSession;
  onPrompt: (prompt: string) => Promise<void>;
  onSettings: () => void;
  onNewSession: () => void;
  onOpenAgents: () => void;
  settings: AppSettings;
  tools: ToolSummary[];
  selectedAgent: UserAgent | null;
  hasAgents: boolean;
}) {
  const [value, setValue] = useState("");
  const [isSending, setIsSending] = useState(false);
  const messagesRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const node = messagesRef.current;
    if (!node) return;
    node.scrollTop = node.scrollHeight;
  }, [session?.messages]);

  async function handleSend(prompt: string) {
    const trimmed = prompt.trim();
    if (!selectedAgent || !trimmed || isSending) return;
    setValue("");
    setIsSending(true);
    try {
      await onPrompt(trimmed);
    } finally {
      setIsSending(false);
    }
  }

  return (
    <section className="panel chat-layout">
      <div className="chat-header">
        <div className="chat-title">
          <div className="eyebrow">Current dialogue</div>
          <h3>{session?.title || "Conversation"}</h3>
          <div className="chat-agent-line">
            {selectedAgent ? (
              <>
                <span className="chat-agent-name">{selectedAgent.name}</span>
                <span className="chat-agent-purpose">{selectedAgent.purpose}</span>
              </>
            ) : (
              <span className="chat-agent-purpose">
                {hasAgents ? "Choose an agent to continue." : "Create an agent to start chatting."}
              </span>
            )}
          </div>
        </div>
        <div className="chat-actions">
          <button className="ghost-button compact" onClick={onOpenAgents}>
            <Bot size={14} />
            Agents
          </button>
          <button className="ghost-button compact" onClick={onNewSession}>
            <Plus size={14} />
            New chat
          </button>
          <button className="ghost-button compact" onClick={onSettings}>
            <Settings2 size={14} />
            Settings
          </button>
        </div>
      </div>

      <div className="chat-overview">
        <div className="overview-card">
          <span className="overview-label">Agent</span>
          <strong>{selectedAgent ? selectedAgent.name : hasAgents ? "Select one" : "None yet"}</strong>
        </div>
        <div className="overview-card">
          <span className="overview-label">Messages</span>
          <strong>{Math.max((session?.messages?.length ?? 1) - 1, 0)}</strong>
        </div>
        <div className="overview-card">
          <span className="overview-label">Tools</span>
          <strong>{tools.length}</strong>
        </div>
        <div className="overview-card">
          <span className="overview-label">Focus</span>
          <strong>{selectedAgent ? selectedAgent.purpose : "Search, summarize, inspect"}</strong>
        </div>
      </div>

      {!selectedAgent && (
        <div className="notice">
          {hasAgents
            ? "Select an agent to use its saved purpose and system prompt."
            : "No agents exist yet. Create one first, then chat with it using its saved purpose and system prompt."}
        </div>
      )}

      <div className="chat-body" ref={messagesRef}>
        {(session?.messages ?? []).map((message) => (
          <MessageCard key={message.id} message={message} />
        ))}
      </div>

      <div className="composer">
        <div className="quick-prompts">
          {QUICK_PROMPTS.map((prompt) => (
            <button
              key={prompt}
              className="chip"
              onClick={() => void handleSend(prompt)}
              disabled={!selectedAgent || isSending}
            >
              <Sparkles size={12} />
              {prompt}
            </button>
          ))}
        </div>

        <div className="composer-row">
          <div className="composer-input">
            <MessageSquare size={16} />
            <textarea
              value={value}
              onChange={(event) => setValue(event.target.value)}
              placeholder={
                selectedAgent
                  ? `Ask ${selectedAgent.name} to search, summarize, or inspect your backend.`
                  : "Create or select an agent before chatting."
              }
              rows={2}
              disabled={!selectedAgent || isSending}
              onKeyDown={(event) => {
                if (event.key === "Enter" && !event.shiftKey) {
                  event.preventDefault();
                  void handleSend(value);
                }
              }}
            />
          </div>
          <button
            className="send-button"
            onClick={() => void handleSend(value)}
            disabled={isSending || !selectedAgent}
            title={!selectedAgent ? "Select an agent first" : undefined}
          >
            {isSending ? <StopCircle size={18} /> : <ArrowRight size={18} />}
          </button>
        </div>

        <div className="composer-foot">
          <span className="footnote">
            POST {settings.baseUrl.replace(/\/+$/, "")}/api/agent/stream
          </span>
          <span className="footnote">{tools.length} tools available</span>
        </div>
      </div>
    </section>
  );
}

function MessageCard({ message }: { message: ChatMessage }) {
  const isUser = message.role === "user";

  return (
    <article className={`message ${isUser ? "user" : "assistant"} ${message.error ? "error" : ""}`}>
      <div className="message-header">
        <div className="message-avatar">{isUser ? <MessageSquare size={14} /> : <ShieldCheck size={14} />}</div>
        <div>
          <div className="message-role">{isUser ? "You" : "Hugin"}</div>
          {!isUser && <div className="message-subtitle">Model response stream</div>}
        </div>
        {!isUser && message.done === false && <LoaderCircle size={15} className="spin" />}
      </div>

      {message.reasoning ? <ReasoningBlock text={message.reasoning} /> : null}
      {message.tools?.length ? <ToolStack tools={message.tools} /> : null}

      <div className={`message-body ${message.content ? "" : "empty"}`}>
        {message.content ? (
          isUser ? (
            <span>{message.content}</span>
          ) : (
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                p: ({ children }) => <p>{children}</p>,
                ul: ({ children }) => <ul>{children}</ul>,
                ol: ({ children }) => <ol>{children}</ol>,
                li: ({ children }) => <li>{children}</li>,
                a: ({ children, href }) => (
                  <a href={href} target="_blank" rel="noreferrer">
                    {children}
                  </a>
                ),
                code: ({ children, className }) => {
                  const inline = !className;
                  return inline ? (
                    <code>{children}</code>
                  ) : (
                    <code className={className}>{children}</code>
                  );
                },
                pre: ({ children }) => <pre>{children}</pre>,
                blockquote: ({ children }) => <blockquote>{children}</blockquote>
              }}
            >
              {message.content}
            </ReactMarkdown>
          )
        ) : (
          "Waiting for the first token..."
        )}
      </div>
    </article>
  );
}

function ReasoningBlock({ text }: { text: string }) {
  const [open, setOpen] = useState(false);

  return (
    <div className="reasoning-block">
      <button className="reasoning-toggle" onClick={() => setOpen((current) => !current)}>
        {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        <Sparkles size={12} />
        reasoning
      </button>
      {open && <div className="reasoning-text">{text}</div>}
    </div>
  );
}

function ToolStack({ tools }: { tools: ToolEvent[] }) {
  return (
    <div className="tool-stack">
      {tools.map((tool, index) => {
        const Icon = TOOL_ICONS[tool.name] ?? Wrench;
        return (
          <ToolRow key={`${tool.name}-${index}`} tool={tool} Icon={Icon} />
        );
      })}
    </div>
  );
}

function ToolRow({ tool, Icon }: { tool: ToolEvent; Icon: typeof Wrench }) {
  const [open, setOpen] = useState(false);

  return (
    <button className="tool-row" onClick={() => setOpen((current) => !current)}>
      <div className="tool-row-head">
        <span className={`tool-state ${tool.state === "done" ? "done" : "running"}`}>
          {tool.state === "done" ? <Check size={12} /> : <LoaderCircle size={12} className="spin" />}
        </span>
        <Icon size={13} />
        <span className="tool-name">{tool.name}</span>
        <ChevronDown size={13} className={open ? "open" : ""} />
      </div>
      {open && (
        <div className="tool-details" onClick={(event) => event.stopPropagation()}>
          <PreBlock label="args" value={tool.args} />
          {tool.result ? <PreBlock label="result" value={tool.result} accent /> : null}
        </div>
      )}
    </button>
  );
}

function PreBlock({
  label,
  value,
  accent = false
}: {
  label: string;
  value: string;
  accent?: boolean;
}) {
  return (
    <div className={`pre-block ${accent ? "accent" : ""}`}>
      <div className="pre-label">{label}</div>
      <pre>{value}</pre>
    </div>
  );
}

function AgentsPanel({
  agents,
  selectedAgentId,
  onSelectAgent,
  onCreateAgent,
  onDeleteAgent,
  busy,
  draftName,
  draftPurpose,
  draftModel,
  onChangeDraftName,
  onChangeDraftPurpose,
  onChangeDraftModel,
  onOpenChat
}: {
  agents: UserAgent[];
  selectedAgentId: string | null;
  onSelectAgent: (agentId: string | null) => void;
  onCreateAgent: () => Promise<void>;
  onDeleteAgent: (id: string) => Promise<void>;
  busy: boolean;
  draftName: string;
  draftPurpose: string;
  draftModel: string;
  onChangeDraftName: (value: string) => void;
  onChangeDraftPurpose: (value: string) => void;
  onChangeDraftModel: (value: string) => void;
  onOpenChat: () => void;
}) {
  const selectedAgent = agents.find((agent) => agent.id === selectedAgentId) || null;
  const systemPromptPreview = useMemo(() => {
    const name = draftName.trim();
    const purpose = draftPurpose.trim();
    if (!name && !purpose) {
      return "Enter a name and purpose to preview the system prompt.";
    }
    return `You are ${name || "this agent"}.\n\nPurpose: ${purpose || "Describe what this agent should do."}`;
  }, [draftName, draftPurpose]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    await onCreateAgent();
  }

  return (
    <section className="panel stack">
      <div className="section-head">
        <div>
          <div className="eyebrow">Agent workshop</div>
          <h3>Create and select agents</h3>
        </div>
        <button className="ghost-button compact" onClick={onOpenChat} disabled={!selectedAgent}>
          <MessageSquare size={14} />
          Open chat
        </button>
      </div>

      <div className="notice">
        When you create an agent, its purpose becomes the seed for its system prompt. Each user
        gets their own agent list, and each agent keeps its own sessions and memory scope.
      </div>

      <div className="agent-workbench">
        <form className="agent-creator card" onSubmit={handleSubmit}>
          <div className="card-head">
            <div>
              <div className="eyebrow">New agent</div>
              <h4>Onboard a purpose-built assistant</h4>
            </div>
            <Badge tone="teal" label="personal" />
          </div>

          <label className="field">
            <span>Name</span>
            <input
              value={draftName}
              onChange={(event) => onChangeDraftName(event.target.value)}
              placeholder="Release manager"
              maxLength={80}
            />
          </label>

          <label className="field">
            <span>Purpose</span>
            <textarea
              value={draftPurpose}
              onChange={(event) => onChangeDraftPurpose(event.target.value)}
              placeholder="Draft release notes, check deployment risk, and summarize blocker status."
              rows={5}
              maxLength={4000}
            />
            <small>The purpose is saved as the agent's initial system prompt.</small>
          </label>

          <label className="field">
            <span>Model override</span>
            <input
              value={draftModel}
              onChange={(event) => onChangeDraftModel(event.target.value)}
              placeholder="Optional: leave blank to use the chat defaults"
              maxLength={120}
            />
            <small>Leave empty to use the app's default routing and model settings.</small>
          </label>

          <div className="agent-form-actions">
            <button
              className="send-button"
              type="submit"
              disabled={busy || !draftName.trim() || !draftPurpose.trim()}
            >
              {busy ? <LoaderCircle size={16} className="spin" /> : <Plus size={16} />}
              Create agent
            </button>
          </div>
        </form>

        <aside className="agent-preview card">
          <div className="card-head">
            <div>
              <div className="eyebrow">Prompt preview</div>
              <h4>What the model will receive</h4>
            </div>
          </div>
          <PreBlock label="system prompt" value={systemPromptPreview} accent />
          <div className="preview-hint">
            This is the starting prompt that will be injected every time the agent is invoked.
          </div>
        </aside>
      </div>

      <div className="section-head compact">
        <div>
          <div className="eyebrow">Your agents</div>
          <h4>{agents.length ? `${agents.length} saved agent${agents.length === 1 ? "" : "s"}` : "No saved agents yet"}</h4>
        </div>
        <div className="section-actions">
          <button className="ghost-button compact" onClick={() => onSelectAgent(null)} disabled={!selectedAgent}>
            <X size={14} />
            Clear selection
          </button>
        </div>
      </div>

      <div className="agent-grid">
        {agents.length ? (
          agents.map((agent) => (
            <article key={agent.id} className={`agent-card ${agent.id === selectedAgentId ? "selected" : ""}`}>
              <div className="agent-status-row">
                <Badge tone={agent.id === selectedAgentId ? "green" : "slate"} label={agent.id === selectedAgentId ? "selected" : "saved"} />
                <span className="footnote">{formatRelativeTime(agent.updatedAt)}</span>
              </div>
              <h4>{agent.name}</h4>
              <p className="agent-purpose">{agent.purpose}</p>
              <div className="agent-meta">
                <span>
                  <MessageSquare size={13} />
                  {agent.model?.trim() ? agent.model : "default model"}
                </span>
                <span>
                  <Sparkles size={13} />
                  {agent.systemPrompt.length} chars
                </span>
              </div>
              <div className="agent-actions">
                <button className="ghost-button compact" onClick={() => onSelectAgent(agent.id)}>
                  <CircleCheckBig size={14} />
                  Select
                </button>
                <button
                  className="danger-button compact"
                  onClick={() => void onDeleteAgent(agent.id)}
                  disabled={busy}
                >
                  <Trash2 size={14} />
                  Delete
                </button>
              </div>
            </article>
          ))
        ) : (
          <div className="empty-state">
            No agents yet. Add one above with a clear purpose and Hugin will reuse that prompt every
            time it invokes the agent.
          </div>
        )}
      </div>
    </section>
  );
}

function SettingsPanel({
  settings,
  onChange,
  onRefresh,
  onRefreshTools,
  tools,
  username
}: {
  settings: AppSettings;
  onChange: (settings: AppSettings) => void;
  onRefresh: () => Promise<void>;
  onRefreshTools: () => Promise<void>;
  tools: ToolSummary[];
  username: string;
}) {
  return (
    <section className="panel stack">
      <div className="section-head">
        <div>
          <div className="eyebrow">Connection profile</div>
          <h3>Settings</h3>
        </div>
        <button className="ghost-button compact" onClick={() => void onRefresh()}>
          <RefreshCw size={14} />
          Test connection
        </button>
      </div>

      <div className="settings-grid">
        <Card title="Backend" icon={ShieldCheck}>
          <LabeledInput
            label="Legacy model"
            value={settings.model || ""}
            onChange={(model) => onChange({ ...settings, model })}
            placeholder="openrouter/owl-alpha"
          />
          <div className="settings-hint">
            Leave routing fields blank if you want the backend to use the legacy model fallback.
          </div>
        </Card>

        <Card title="Session" icon={LogOut}>
          <div className="settings-summary">
            <SummaryRow label="Signed in as" value={username || "unknown"} />
            <SummaryRow label="Transport" value="Bearer JWT" />
          </div>
          <div className="settings-hint">
            The app keeps the token in browser storage and sends it on future requests as an Authorization header.
          </div>
        </Card>

        <Card title="LLM routing" icon={Sparkles}>
          <LabeledInput
            label="Decision model"
            value={settings.decision}
            onChange={(decision) => onChange({ ...settings, decision })}
            placeholder="llama3.2"
          />
          <LabeledInput
            label="Complex model"
            value={settings.complex}
            onChange={(complex) => onChange({ ...settings, complex })}
            placeholder="openai/gpt-oss-120b"
          />
          <LabeledInput
            label="Simple model"
            value={settings.simple}
            onChange={(simple) => onChange({ ...settings, simple })}
            placeholder="openai/gpt-oss-20b"
          />
          <div className="settings-hint">
            Fill all three routing models to enable complexity-based routing. Otherwise Hugin uses
            the legacy model fallback above.
          </div>
        </Card>

        <Card title="Quick sync" icon={CircleCheckBig}>
          <button className="ghost-button full" onClick={() => void onRefreshTools()}>
            <Wrench size={14} />
            Refresh tools
          </button>
          <div className="settings-summary">
            <SummaryRow label="Tools" value={`${tools.length}`} />
          </div>
        </Card>
      </div>
    </section>
  );
}

function LoginScreen({
  username,
  password,
  onChangeUsername,
  onChangePassword,
  onSubmit,
  busy,
  message,
  error,
  status
}: {
  username: string;
  password: string;
  onChangeUsername: (value: string) => void;
  onChangePassword: (value: string) => void;
  onSubmit: (event: FormEvent) => Promise<void>;
  busy: boolean;
  message: string;
  error: string;
  status: AuthState;
}) {
  return (
    <div className="auth-shell">
      <div className="auth-backdrop" />
      <div className="auth-card">
        <div className="auth-hero">
          <div className="brand-mark auth-mark">
            <Sparkles size={18} />
          </div>
          <div className="eyebrow">Hugin</div>
          <h1>Secure sign in</h1>
          <p>
            Authenticate once, then the app uses a bearer JWT for every API request to the Hugin backend.
          </p>
        </div>

        <form className="auth-form" onSubmit={(event) => void onSubmit(event)}>
          <LabeledInput
            label="Username"
            value={username}
            onChange={onChangeUsername}
            placeholder="test"
          />
          <LabeledInput
            label="Password"
            value={password}
            onChange={onChangePassword}
            placeholder="password"
            type="password"
          />
          <button className="send-button auth-submit" type="submit" disabled={busy}>
            {busy ? <LoaderCircle size={18} className="spin" /> : <ShieldCheck size={18} />}
            Sign in
          </button>
        </form>

        <div className={`auth-message ${error ? "error" : ""}`}>
          {error || message || (status === "loading" ? "Checking stored session..." : "Ready")}
        </div>
      </div>
    </div>
  );
}

function Card({
  title,
  icon: Icon,
  children
}: {
  title: string;
  icon: typeof ShieldCheck;
  children: ReactNode;
}) {
  return (
    <div className="card">
      <div className="card-head">
        <div className="card-title">
          <Icon size={16} />
          {title}
        </div>
      </div>
      {children}
    </div>
  );
}

function SummaryRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="summary-row">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function LabeledInput({
  label,
  value,
  onChange,
  placeholder,
  type = "text"
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  type?: string;
}) {
  return (
    <label className="field">
      <span>{label}</span>
      <input
        type={type}
        value={value}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  );
}

export default App;
