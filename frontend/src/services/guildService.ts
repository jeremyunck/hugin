import type {
  AppearanceSettings,
  AuthSession,
  ChatMessage,
  ChatThread,
  ConnectedServiceStatus,
  GoogleWorkspaceState,
  GuildState,
  IntegrationItem
} from "../lib/types";

const STORAGE_KEY = "guild-app-state-v2";
const AUTH_STORAGE_KEY = "guild-auth-session-v1";

type AgentResponse = {
  response: string;
};

type GoogleWorkspaceStatusResponse = {
  active: boolean;
  configured: boolean;
  reconnectable: boolean;
  authMode: "oauth" | "service-account" | "none";
  message: string;
};

type GoogleReconnectResponse = {
  status: GoogleWorkspaceStatusResponse;
  authUrl: string | null;
};

type AuthLoginResponse = {
  token: string;
  tokenType: string;
  expiresAt: string;
  username: string;
  roles: string[];
};

type AuthMeResponse = {
  username: string;
  roles: string[];
  issuedAt: string;
  expiresAt: string;
};

function nowIso() {
  return new Date().toISOString();
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

function createGoogleWorkspaceState(overrides: Partial<GoogleWorkspaceState> = {}): GoogleWorkspaceState {
  return {
    accountName: "Not connected",
    authStatus: "not-connected",
    lastRefreshedAt: nowIso(),
    authMode: "none",
    configured: false,
    reconnectable: false,
    message: "Google Workspace is not configured.",
    connectedServices: [
      { label: "Gmail", status: "not-connected" },
      { label: "Calendar", status: "not-connected" },
      { label: "Docs", status: "not-connected" },
      { label: "Sheets", status: "not-connected" }
    ],
    ...overrides
  };
}

function buildSeedState(): GuildState {
  return {
    threads: [],
    appearance: {
      theme: "light",
      textSize: "medium",
      reduceMotion: false
    },
    integrations: {
      list: [],
      googleWorkspace: createGoogleWorkspaceState()
    }
  };
}

function withDerivedIntegrations(state: GuildState): GuildState {
  return {
    ...state,
    integrations: {
      ...state.integrations,
      list: [googleIntegrationItem(state.integrations.googleWorkspace)]
    }
  };
}

export function loadGuildState(): GuildState {
  if (typeof window === "undefined") return withDerivedIntegrations(buildSeedState());

  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (!raw) return withDerivedIntegrations(buildSeedState());

  try {
    const parsed = JSON.parse(raw) as Partial<GuildState>;
    const seed = buildSeedState();
    return withDerivedIntegrations({
      threads: Array.isArray(parsed.threads) ? (parsed.threads as ChatThread[]) : seed.threads,
      appearance: {
        ...seed.appearance,
        ...(parsed.appearance || {})
      } as AppearanceSettings,
      integrations: {
        list: [],
        googleWorkspace: createGoogleWorkspaceState(parsed.integrations?.googleWorkspace || {})
      }
    });
  } catch {
    return withDerivedIntegrations(buildSeedState());
  }
}

export function saveGuildState(state: GuildState) {
  if (typeof window === "undefined") return;
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify({
    threads: state.threads,
    appearance: state.appearance,
    integrations: {
      googleWorkspace: state.integrations.googleWorkspace
    }
  }));
}

export function loadAuthSession(): AuthSession | null {
  if (typeof window === "undefined") return null;
  const raw = window.sessionStorage.getItem(AUTH_STORAGE_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as AuthSession;
    if (!parsed.token || !parsed.username) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function saveAuthSession(session: AuthSession | null) {
  if (typeof window === "undefined") return;
  if (!session) {
    window.sessionStorage.removeItem(AUTH_STORAGE_KEY);
    return;
  }
  window.sessionStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(session));
}

async function apiFetch<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const headers = new Headers(init.headers || {});
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(path, { ...init, headers });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body && typeof body.error === "string" && body.error) {
        message = body.error;
      }
    } catch {
      // Ignore parse errors and keep the status text fallback.
    }
    const error = new Error(message) as Error & { status?: number };
    error.status = response.status;
    throw error;
  }
  return (await response.json()) as T;
}

export async function login(username: string, password: string): Promise<AuthSession> {
  const response = await apiFetch<AuthLoginResponse>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ username, password })
  });
  return {
    token: response.token,
    username: response.username,
    roles: response.roles,
    expiresAt: response.expiresAt
  };
}

export async function fetchCurrentUser(token: string): Promise<AuthSession> {
  const response = await apiFetch<AuthMeResponse>("/api/auth/me", {}, token);
  return {
    token,
    username: response.username,
    roles: response.roles,
    expiresAt: response.expiresAt
  };
}

export async function sendPrompt(token: string, threadId: string, prompt: string): Promise<string> {
  const response = await apiFetch<AgentResponse>("/api/agent/chat", {
    method: "POST",
    body: JSON.stringify({
      prompt,
      sessionId: threadId
    })
  }, token);
  return response.response || "";
}

export async function fetchGoogleWorkspaceStatus(token: string): Promise<GoogleWorkspaceState> {
  const response = await apiFetch<GoogleWorkspaceStatusResponse>("/api/google/status", {}, token);
  return mapGoogleWorkspaceStatus(response);
}

export async function reconnectGoogleWorkspace(token: string): Promise<GoogleWorkspaceState> {
  const response = await apiFetch<GoogleReconnectResponse>("/api/google/reconnect", {
    method: "POST",
    body: JSON.stringify({ returnTo: window.location.href })
  }, token);
  if (response.authUrl) {
    window.location.assign(response.authUrl);
  }
  return mapGoogleWorkspaceStatus(response.status);
}

export async function disconnectGoogleWorkspace(token: string): Promise<GoogleWorkspaceState> {
  const response = await apiFetch<GoogleWorkspaceStatusResponse>("/api/google/disconnect", {
    method: "POST"
  }, token);
  return mapGoogleWorkspaceStatus(response);
}

function mapGoogleWorkspaceStatus(status: GoogleWorkspaceStatusResponse): GoogleWorkspaceState {
  const serviceStatus: ConnectedServiceStatus = status.active
    ? "connected"
    : status.configured
      ? "attention"
      : "not-connected";

  return createGoogleWorkspaceState({
    accountName: googleAccountLabel(status),
    authStatus: status.active ? "connected" : status.configured ? "attention" : "not-connected",
    lastRefreshedAt: nowIso(),
    authMode: status.authMode,
    configured: status.configured,
    reconnectable: status.reconnectable,
    message: status.message,
    connectedServices: [
      { label: "Gmail", status: serviceStatus },
      { label: "Calendar", status: serviceStatus },
      { label: "Docs", status: serviceStatus },
      { label: "Sheets", status: serviceStatus }
    ]
  });
}

function googleAccountLabel(status: GoogleWorkspaceStatusResponse) {
  if (!status.configured) return "Not configured";
  if (status.authMode === "service-account") return "Service account";
  if (status.active) return "OAuth connected";
  return "OAuth needs consent";
}

function googleIntegrationItem(googleWorkspace: GoogleWorkspaceState): IntegrationItem {
  return {
    id: "google-workspace",
    label: "Google Workspace",
    subtitle: googleWorkspace.message,
    status: googleWorkspace.authStatus,
    detail: googleWorkspace.authStatus === "connected"
      ? "Connected"
      : googleWorkspace.authStatus === "attention"
        ? "Needs attention"
        : "Not connected"
  };
}

export function setGoogleWorkspaceState(state: GuildState, googleWorkspace: GoogleWorkspaceState): GuildState {
  return withDerivedIntegrations({
    ...state,
    integrations: {
      ...state.integrations,
      googleWorkspace
    }
  });
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

export function addThread(state: GuildState, thread: ChatThread) {
  return {
    ...state,
    threads: [thread, ...state.threads]
  };
}

export function appendAssistantReply(state: GuildState, threadId: string, prompt: string, response: string): GuildState {
  const thread = getThread(state, threadId);
  if (!thread) return state;

  const createdAt = nowIso();
  const userMessage = threadMessage("user", prompt, createdAt);
  const assistantMessage = threadMessage("assistant", response, nowIso());

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

export function clearHistory(state: GuildState) {
  return {
    ...state,
    threads: state.threads
      .map((thread) => ({ ...thread, messages: [], updatedAt: thread.createdAt }))
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
