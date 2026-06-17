import type {
  AppState,
  AuthSession,
  ChatAttachment,
  ChatEntry,
  ChatKind,
  ChatThread,
  FileNode,
  Integration,
  SandboxInfo,
  StreamToolEvent,
  ToolSummary
} from "../lib/types";

const APP_STORAGE_KEY = "hugin-minimal-ui-state-v1";
const AUTH_STORAGE_KEY = "hugin-auth-session-v1";

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

export type StreamEvent =
  | { type: "config"; developerMode: boolean }
  | { type: "token"; text: string }
  | { type: "reasoning"; text: string }
  | { type: "tool"; name: string; args: string }
  | { type: "tool_result"; name: string; result: string }
  | { type: "done" }
  | { type: "error"; message: string };

type StreamHandlers = {
  onEvent: (event: StreamEvent) => void;
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

export function createEmptyState(): AppState {
  return { threads: [] };
}

export function loadAppState(): AppState {
  if (typeof window === "undefined") return createEmptyState();
  const raw = window.localStorage.getItem(APP_STORAGE_KEY);
  if (!raw) return createEmptyState();

  try {
    const parsed = JSON.parse(raw) as Partial<AppState>;
    return {
      threads: Array.isArray(parsed.threads) ? parsed.threads : []
    };
  } catch {
    return createEmptyState();
  }
}

export function saveAppState(state: AppState) {
  if (typeof window === "undefined") return;
  const sanitized: AppState = {
    ...state,
    threads: state.threads.map((thread) => ({
      ...thread,
      entries: thread.entries.map((entry) =>
        entry.type !== "user" || !entry.attachments?.length
          ? entry
          : {
              ...entry,
              // Avoid exhausting localStorage with base64 payloads; active chat state keeps the
              // live preview, while persisted history retains only metadata.
              attachments: entry.attachments.map((attachment) => ({
                name: attachment.name,
                mimeType: attachment.mimeType,
                size: attachment.size
              }))
            }
      )
    }))
  };
  window.localStorage.setItem(APP_STORAGE_KEY, JSON.stringify(sanitized));
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
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(path, { ...init, headers });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body && typeof body.error === "string" && body.error) {
        message = body.error;
      }
    } catch {
      // Keep the status fallback when no JSON body is available.
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

export function createThread(kind: ChatKind = "chat", sandboxId?: string): ChatThread {
  const createdAt = nowIso();
  return {
    id: uid("thread"),
    title: kind === "sandbox" ? "New sandbox" : "New chat",
    kind,
    sandboxId,
    createdAt,
    updatedAt: createdAt,
    entries: []
  };
}

export function getThreadTitle(prompt: string) {
  const normalized = prompt.trim().replace(/\s+/g, " ");
  if (!normalized) return "New chat";
  return normalized.length > 42 ? `${normalized.slice(0, 42).trimEnd()}...` : normalized;
}

export function addThread(state: AppState, thread: ChatThread): AppState {
  return {
    ...state,
    threads: [thread, ...state.threads]
  };
}

export function updateThread(state: AppState, threadId: string, updater: (thread: ChatThread) => ChatThread): AppState {
  return {
    ...state,
    threads: state.threads.map((thread) => (thread.id === threadId ? updater(thread) : thread))
  };
}

export function formatTimestamp(iso: string) {
  return new Intl.DateTimeFormat("en", {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit"
  }).format(new Date(iso));
}

export function buildUserEntry(content: string, attachments?: ChatAttachment[]): ChatEntry {
  return {
    id: uid("user"),
    type: "user",
    content,
    ...(attachments?.length ? { attachments } : {}),
    createdAt: nowIso()
  };
}

export function buildAssistantEntry(): Extract<ChatEntry, { type: "assistant" }> {
  return {
    id: uid("assistant"),
    type: "assistant",
    content: "",
    reasoning: "",
    createdAt: nowIso()
  };
}

function buildToolEvent(name: string, args: string): StreamToolEvent {
  return {
    id: uid("tool"),
    name,
    args,
    result: "",
    startedAt: nowIso()
  };
}

export function appendEntries(
  state: AppState,
  threadId: string,
  entries: ChatEntry[],
  titleOverride?: string
): AppState {
  return updateThread(state, threadId, (thread) => ({
    ...thread,
    title: titleOverride ?? thread.title,
    updatedAt: nowIso(),
    entries: [...thread.entries, ...entries]
  }));
}

export function appendAssistantDelta(state: AppState, threadId: string, assistantId: string, delta: string): AppState {
  return updateThread(state, threadId, (thread) => ({
    ...thread,
    updatedAt: nowIso(),
    entries: thread.entries.map((entry) =>
      entry.type === "assistant" && entry.id === assistantId
        ? { ...entry, content: `${entry.content}${delta}` }
        : entry
    )
  }));
}

export function appendReasoningDelta(state: AppState, threadId: string, assistantId: string, delta: string): AppState {
  return updateThread(state, threadId, (thread) => ({
    ...thread,
    updatedAt: nowIso(),
    entries: thread.entries.map((entry) =>
      entry.type === "assistant" && entry.id === assistantId
        ? { ...entry, reasoning: `${entry.reasoning}${delta}` }
        : entry
    )
  }));
}

export function appendToolCall(state: AppState, threadId: string, name: string, args: string): AppState {
  return appendEntries(state, threadId, [
    {
      id: uid("entry-tool"),
      type: "tool",
      tool: buildToolEvent(name, args),
      createdAt: nowIso()
    }
  ]);
}

export function attachToolResult(state: AppState, threadId: string, name: string, result: string): AppState {
  return updateThread(state, threadId, (thread) => {
    let updated = false;
    return {
      ...thread,
      updatedAt: nowIso(),
      entries: thread.entries.map((entry) => {
        if (updated || entry.type !== "tool" || entry.tool.name !== name || entry.tool.finishedAt) return entry;
        updated = true;
        return {
          ...entry,
          tool: {
            ...entry.tool,
            result,
            finishedAt: nowIso()
          }
        };
      })
    };
  });
}

export function completeAssistantEntry(state: AppState, threadId: string, assistantId: string): AppState {
  return updateThread(state, threadId, (thread) => ({
    ...thread,
    updatedAt: nowIso(),
    entries: thread.entries.map((entry) =>
      entry.type === "assistant" && entry.id === assistantId
        ? { ...entry, completedAt: nowIso() }
        : entry
    )
  }));
}

export type StreamOptions = {
  threadId: string;
  prompt: string;
  attachments?: ChatAttachment[];
  /** When set, the agent runs inside this sandbox and gets filesystem/shell tools. */
  sandboxId?: string;
};

export async function streamPrompt(token: string, options: StreamOptions, handlers: StreamHandlers) {
  const response = await fetch("/api/agent/stream", {
    method: "POST",
    headers: {
      Accept: "text/event-stream",
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify({
      prompt: options.prompt,
      ...(options.attachments?.length ? { attachments: options.attachments } : {}),
      sessionId: options.threadId,
      // Only sandbox sessions advertise filesystem tools; pure chats omit sandboxId entirely.
      ...(options.sandboxId ? { sandboxId: options.sandboxId } : {})
    })
  });

  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body && typeof body.error === "string" && body.error) {
        message = body.error;
      }
    } catch {
      // Keep the status fallback when no JSON body is available.
    }
    const error = new Error(message) as Error & { status?: number };
    error.status = response.status;
    throw error;
  }

  if (!response.body) {
    throw new Error("Stream body was not available.");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split("\n\n");
    buffer = parts.pop() ?? "";

    for (const rawEvent of parts) {
      const event = parseSseEvent(rawEvent);
      if (event) handlers.onEvent(event);
    }
  }

  buffer += decoder.decode();
  const finalEvent = parseSseEvent(buffer);
  if (finalEvent) handlers.onEvent(finalEvent);
}

export async function createSandbox(token: string): Promise<SandboxInfo> {
  return apiFetch<SandboxInfo>("/api/sandboxes", { method: "POST", body: JSON.stringify({}) }, token);
}

export async function deleteSandbox(token: string, id: string): Promise<void> {
  await fetch(`/api/sandboxes/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` }
  });
}

export async function fetchSandboxFiles(token: string, id: string): Promise<FileNode[]> {
  return apiFetch<FileNode[]>(`/api/sandboxes/${encodeURIComponent(id)}/files`, {}, token);
}

export async function fetchIntegrations(token: string): Promise<Integration[]> {
  return apiFetch<Integration[]>("/api/integrations", {}, token);
}

export async function fetchTools(token: string, sandboxId?: string): Promise<ToolSummary[]> {
  const query = sandboxId ? `?sandboxId=${encodeURIComponent(sandboxId)}` : "";
  return apiFetch<ToolSummary[]>(`/api/agent/tools${query}`, {}, token);
}

type GoogleReconnectResponse = {
  status: Integration | Record<string, unknown>;
  authUrl: string | null;
};

export async function reconnectGoogle(token: string, returnTo: string): Promise<string | null> {
  const response = await apiFetch<GoogleReconnectResponse>(
    "/api/google/reconnect",
    { method: "POST", body: JSON.stringify({ returnTo }) },
    token
  );
  return response.authUrl;
}

export async function disconnectGoogle(token: string): Promise<void> {
  await apiFetch("/api/google/disconnect", { method: "POST", body: JSON.stringify({}) }, token);
}

type GitHubConnectResponse = {
  status?: {
    message?: string;
  };
  installUrl: string | null;
};

export async function connectGitHub(token: string, returnTo: string): Promise<GitHubConnectResponse> {
  return apiFetch<GitHubConnectResponse>(
    "/api/github/connect",
    { method: "POST", body: JSON.stringify({ returnTo }) },
    token
  );
}

export async function disconnectGitHub(token: string): Promise<void> {
  await apiFetch("/api/github/disconnect", { method: "POST", body: JSON.stringify({}) }, token);
}

function parseSseEvent(rawEvent: string): StreamEvent | null {
  const lines = rawEvent
    .split(/\r?\n/)
    .map((line) => line.trimEnd())
    .filter(Boolean);

  if (!lines.length) return null;

  let eventName = "";
  const dataLines: string[] = [];

  for (const line of lines) {
    if (line.startsWith("event:")) {
      eventName = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      dataLines.push(line.slice(5).trim());
    }
  }

  if (!eventName || !dataLines.length) return null;

  const payload = JSON.parse(dataLines.join("\n")) as Record<string, unknown>;
  switch (eventName) {
    case "config":
      return { type: "config", developerMode: payload.developerMode === true };
    case "token":
      return { type: "token", text: String(payload.text ?? "") };
    case "reasoning":
      return { type: "reasoning", text: String(payload.text ?? "") };
    case "tool":
      return {
        type: "tool",
        name: String(payload.name ?? "tool"),
        args: String(payload.args ?? "")
      };
    case "tool_result":
      return {
        type: "tool_result",
        name: String(payload.name ?? "tool"),
        result: String(payload.result ?? "")
      };
    case "done":
      return { type: "done" };
    case "error":
      return { type: "error", message: String(payload.message ?? "Stream failed.") };
    default:
      return null;
  }
}
