import type {
  AppState,
  AuthSession,
  BugReportSummary,
  ChatActivity,
  ChatAttachment,
  ChatEntry,
  ChatKind,
  ChatMessage,
  ChatThread,
  FileNode,
  GitHubBranch,
  GitHubRepository,
  GitHubStatus,
  Integration,
  BugReportResponse,
  AgentRun,
  ModelOption,
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

export type ChatEvent = {
  id: string;
  seq: number;
  type: string;
  messageId?: string | null;
  runId?: string | null;
  role?: string | null;
  content?: string | null;
  metadata?: Record<string, unknown> | null;
  createdAt: string;
};

type ChatEventsResponse = {
  sessionId: string;
  events: ChatEvent[];
};

type SendChatMessageOptions = {
  content: string;
  mode: "CHAT" | "SANDBOX" | "GITHUB";
  title: string;
  attachments?: ChatAttachment[];
  model?: string;
  reasoningEffort?: string;
  sandboxId?: string;
};

type SendChatMessageResponse = {
  sessionId: string;
  messageId: string;
  runId: string;
  lastSeq: number;
};

type ChatEventStreamHandlers = {
  onEvent: (event: ChatEvent) => void;
  onStatus?: (status: "connecting" | "open" | "reconnecting" | "closed" | "error") => void;
  onError?: (error: Error) => void;
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

export async function sendChatMessage(
  token: string,
  sessionId: string,
  options: SendChatMessageOptions
): Promise<SendChatMessageResponse> {
  return apiFetch<SendChatMessageResponse>(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`,
    {
      method: "POST",
      body: JSON.stringify({
        content: options.content,
        mode: options.mode,
        title: options.title,
        ...(options.attachments?.length ? { attachments: options.attachments } : {}),
        ...(options.model ? { model: options.model } : {}),
        ...(options.reasoningEffort ? { reasoningEffort: options.reasoningEffort } : {}),
        ...(options.sandboxId ? { sandboxId: options.sandboxId } : {})
      })
    },
    token
  );
}

export async function fetchChatSessionEvents(token: string, sessionId: string, afterSeq = 0): Promise<ChatEvent[]> {
  const query = afterSeq > 0 ? `?afterSeq=${afterSeq}` : "";
  const response = await apiFetch<ChatEventsResponse>(
    `/api/chat/sessions/${encodeURIComponent(sessionId)}/events${query}`,
    {},
    token
  );
  return response.events;
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

type CreateThreadOptions = {
  sandboxId?: string;
  repoFullName?: string;
  repoName?: string;
  branchName?: string;
};

export function createThread(kind: ChatKind = "chat", options: CreateThreadOptions = {}): ChatThread {
  const createdAt = nowIso();
  const title = kind === "sandbox"
    ? "New sandbox"
    : kind === "github"
    ? `${options.repoFullName ?? "GitHub repo"}${options.branchName ? ` (${options.branchName})` : ""}`
    : "New chat";
  return {
    id: uid("thread"),
    title,
    kind,
    sandboxId: options.sandboxId,
    repoFullName: options.repoFullName,
    repoName: options.repoName,
    branchName: options.branchName,
    modelId: undefined,
    reasoningEffort: undefined,
    createdAt,
    updatedAt: createdAt,
    entries: [],
    activities: [],
    lastSeq: 0,
    connectionStatus: "idle"
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

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function errorFromResponse(response: Response): Promise<Error & { status?: number }> {
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
  return error;
}

export type ServerChatMessage = {
  role: "user" | "assistant" | "system" | "tool";
  content: string;
  attachments?: ChatAttachment[];
  reasoning_content?: string | null;
  tool_calls?: Array<{
    id?: string | null;
    function?: {
      name?: string | null;
      arguments?: string | null;
    } | null;
  }> | null;
  tool_call_id?: string | null;
};

async function fetchConversationHistory(token: string, sessionId: string): Promise<ServerChatMessage[]> {
  return apiFetch<ServerChatMessage[]>(`/api/agent/history?sessionId=${encodeURIComponent(sessionId)}`, {}, token);
}

function hasRecoveredTurn(history: ServerChatMessage[], priorMessageCount: number, prompt: string): boolean {
  let lastUserIndex = -1;
  for (let index = history.length - 1; index >= 0; index -= 1) {
    const message = history[index];
    if (message.role === "user" && (message.content ?? "") === prompt) {
      lastUserIndex = index;
      break;
    }
  }
  if (lastUserIndex < priorMessageCount || lastUserIndex === -1) {
    return false;
  }
  const tail = history.slice(lastUserIndex + 1);
  if (!tail.length) {
    return false;
  }
  return tail.some((message) => {
    if (message.role === "tool") {
      return true;
    }
    if (message.role !== "assistant") {
      return false;
    }
    return Boolean(
      (message.content ?? "").trim()
      || (message.reasoning_content ?? "").trim()
      || (message.tool_calls?.length ?? 0) > 0
    );
  });
}

export function rebuildThreadFromHistory(thread: ChatThread, history: ServerChatMessage[]): ChatThread {
  const remote = history.filter((message) =>
    message.role === "user" || message.role === "assistant" || message.role === "tool");
  if (!remote.length) {
    return thread;
  }
  const entries: ChatEntry[] = [];
  const toolEntriesByCallId = new Map<string, number>();

  for (const message of remote) {
    if (message.role === "user") {
      entries.push(buildUserEntry(message.content ?? "", message.attachments));
      continue;
    }

    if (message.role === "assistant") {
      const hasVisibleAssistantContent = Boolean((message.content ?? "").trim() || (message.reasoning_content ?? "").trim());
      if (hasVisibleAssistantContent) {
        entries.push({
          ...buildAssistantEntry(),
          content: message.content ?? "",
          reasoning: message.reasoning_content ?? "",
          completedAt: nowIso()
        });
      }
      for (const toolCall of message.tool_calls ?? []) {
        const toolEntry: ChatEntry = {
          id: uid("entry-tool"),
          type: "tool",
          tool: {
            id: uid("tool"),
            callId: toolCall.id ?? undefined,
            name: toolCall.function?.name ?? "tool",
            args: toolCall.function?.arguments ?? "",
            result: "",
            startedAt: nowIso()
          },
          createdAt: nowIso()
        };
        entries.push(toolEntry);
        if (toolCall.id) {
          toolEntriesByCallId.set(toolCall.id, entries.length - 1);
        }
      }
      continue;
    }

    const byCallId = message.tool_call_id ? toolEntriesByCallId.get(message.tool_call_id) : undefined;
    const matchIndex = byCallId ?? [...entries].reverse().findIndex((entry) =>
      entry.type === "tool" && !entry.tool.finishedAt);
    const entryIndex = byCallId ?? (matchIndex === -1 ? -1 : entries.length - 1 - matchIndex);
    if (entryIndex >= 0 && entries[entryIndex]?.type === "tool") {
      const entry = entries[entryIndex] as Extract<ChatEntry, { type: "tool" }>;
      entries[entryIndex] = {
        ...entry,
        tool: {
          ...entry.tool,
          result: message.content ?? "",
          finishedAt: nowIso()
        }
      };
    }
  }

  return {
    ...thread,
    updatedAt: nowIso(),
    entries
  };
}

export async function recoverThreadAfterDroppedStream(
  token: string,
  thread: ChatThread,
  priorMessageCount: number,
  prompt: string
): Promise<ChatThread | null> {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const history = await fetchConversationHistory(token, thread.id);
      if (hasRecoveredTurn(history, priorMessageCount, prompt)) {
        return rebuildThreadFromHistory(thread, history);
      }
    } catch {
      // Ignore transient polling failures and keep trying until the deadline.
    }
    await delay(1000);
  }
  return null;
}

export function buildPriorMessages(thread: ChatThread): ChatMessage[] {
  const messages: ChatMessage[] = [];
  for (const entry of thread.entries) {
    if (entry.type === "user") {
      messages.push({
        role: "user" as const,
        content: entry.content,
        attachments: entry.attachments
      });
      continue;
    }
    if (entry.type === "assistant") {
      messages.push({
        role: "assistant" as const,
        content: entry.content,
        reasoning_content: entry.reasoning || undefined
      });
    }
  }
  return messages;
}

export async function syncThreadHistory(token: string, thread: ChatThread): Promise<ChatThread> {
  const history = await fetchConversationHistory(token, thread.id);
  return rebuildThreadFromHistory(thread, history);
}

/** Forgets the server-side conversation memory for a session. Best-effort; ignores a missing session. */
export async function deleteThreadHistory(token: string, sessionId: string): Promise<void> {
  await fetch(`/api/agent/history?sessionId=${encodeURIComponent(sessionId)}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` }
  });
}

export function removeThread(state: AppState, threadId: string): AppState {
  return {
    ...state,
    threads: state.threads.filter((thread) => thread.id !== threadId)
  };
}

export async function createSandbox(token: string): Promise<SandboxInfo> {
  return apiFetch<SandboxInfo>("/api/sandboxes", { method: "POST", body: JSON.stringify({}) }, token);
}

export async function createGitHubSandbox(
  token: string,
  repoFullName: string,
  branch: string,
  bugReportId?: string
): Promise<SandboxInfo> {
  return apiFetch<SandboxInfo>(
    "/api/sandboxes/github",
    { method: "POST", body: JSON.stringify({ repoFullName, branch, bugReportId }) },
    token
  );
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

type ModelSettingsResponse = {
  models: ModelOption[];
};

export async function fetchModels(token: string, enabledOnly = false): Promise<ModelOption[]> {
  const query = enabledOnly ? "?enabledOnly=true" : "";
  const response = await apiFetch<ModelSettingsResponse>(`/api/models${query}`, {}, token);
  return response.models;
}

export async function saveEnabledModels(token: string, enabledModelIds: string[]): Promise<ModelOption[]> {
  const response = await apiFetch<ModelSettingsResponse>(
    "/api/models/preferences",
    { method: "PUT", body: JSON.stringify({ enabledModelIds }) },
    token
  );
  return response.models;
}

export async function fetchTools(token: string, sandboxId?: string): Promise<ToolSummary[]> {
  const query = sandboxId ? `?sandboxId=${encodeURIComponent(sandboxId)}` : "";
  return apiFetch<ToolSummary[]>(`/api/agent/tools${query}`, {}, token);
}

export async function fetchAgentRuns(token: string): Promise<AgentRun[]> {
  return apiFetch<AgentRun[]>("/api/agent/runs", {}, token);
}

export async function cancelAgentRun(token: string, id: string): Promise<void> {
  const response = await fetch(`/api/agent/runs/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` }
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
    throw new Error(message);
  }
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

export async function fetchGitHubStatus(token: string): Promise<GitHubStatus> {
  return apiFetch<GitHubStatus>("/api/github/status", {}, token);
}

export async function fetchGitHubRepositories(token: string): Promise<GitHubRepository[]> {
  return apiFetch<GitHubRepository[]>("/api/github/repositories", {}, token);
}

export async function fetchGitHubBranches(token: string, repoFullName: string): Promise<GitHubBranch[]> {
  const [owner, repo] = repoFullName.split("/", 2);
  if (!owner || !repo) {
    throw new Error("Repository must be in owner/repo format.");
  }
  return apiFetch<GitHubBranch[]>(
    `/api/github/repositories/${encodeURIComponent(owner)}/${encodeURIComponent(repo)}/branches`,
    {},
    token
  );
}

type ReportBugRequest = {
  sessionId: string;
  title: string;
  sandboxId?: string;
  agentId?: string;
  thread: unknown;
  clientContext: unknown;
};

export async function reportBug(token: string, payload: ReportBugRequest): Promise<BugReportResponse> {
  return apiFetch<BugReportResponse>(
    "/api/agent/bug-report",
    { method: "POST", body: JSON.stringify(payload) },
    token
  );
}

export async function fetchBugReports(token: string): Promise<BugReportSummary[]> {
  return apiFetch<BugReportSummary[]>("/api/agent/bug-reports", {}, token);
}

function parseChatSseEvent(rawEvent: string): ChatEvent | null {
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

  if (eventName !== "chat_event" || !dataLines.length) return null;
  return JSON.parse(dataLines.join("\n")) as ChatEvent;
}

export function openChatEventStream(
  token: string,
  sessionId: string,
  initialAfterSeq: number,
  handlers: ChatEventStreamHandlers
) {
  const controller = new AbortController();
  let afterSeq = initialAfterSeq;
  let closed = false;

  const run = async () => {
    let attempt = 0;
    while (!closed) {
      handlers.onStatus?.(attempt === 0 ? "connecting" : "reconnecting");
      try {
        const response = await fetch(
          `/api/chat/sessions/${encodeURIComponent(sessionId)}/stream?afterSeq=${encodeURIComponent(String(afterSeq))}`,
          {
            method: "GET",
            headers: {
              Accept: "text/event-stream",
              Authorization: `Bearer ${token}`
            },
            signal: controller.signal
          }
        );
        if (!response.ok) {
          throw await errorFromResponse(response);
        }
        if (!response.body) {
          throw new Error("Stream body was not available.");
        }

        handlers.onStatus?.("open");
        attempt = 0;
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        while (!closed) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          const parts = buffer.split("\n\n");
          buffer = parts.pop() ?? "";
          for (const rawEvent of parts) {
            const event = parseChatSseEvent(rawEvent);
            if (!event) continue;
            afterSeq = Math.max(afterSeq, event.seq);
            handlers.onEvent(event);
          }
        }

        if (closed) {
          return;
        }
      } catch (error) {
        if (closed || controller.signal.aborted) {
          return;
        }
        handlers.onError?.(error instanceof Error ? error : new Error("Chat stream failed."));
      }

      attempt += 1;
      await delay(Math.min(5000, 1000 * attempt));
    }
  };

  void run();
  return {
    close() {
      closed = true;
      controller.abort();
      handlers.onStatus?.("closed");
    }
  };
}
