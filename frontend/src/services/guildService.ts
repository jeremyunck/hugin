import type {
  AppState,
  AuthSession,
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

export type StreamEvent =
  | { type: "config"; developerMode: boolean }
  | { type: "token"; text: string }
  | { type: "reasoning"; text: string }
  | { type: "tool"; name: string; args: string }
  | { type: "tool_result"; name: string; result: string }
  | { type: "replace"; content: string }
  // Emitted client-side (never by the server) before a dropped stream is replayed, so the UI can
  // discard the partial answer from the failed attempt and stream the fresh one cleanly.
  | { type: "reset" }
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
  priorMessages?: ChatMessage[];
  model?: string;
  reasoningEffort?: string;
  /** When set, the agent runs inside this sandbox and gets filesystem/shell tools. */
  sandboxId?: string;
};

// Backoff schedule for reconnecting a dropped stream. The length also bounds the attempt count.
const STREAM_RECONNECT_DELAYS_MS = [1000, 2000, 4000];

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Status codes worth reconnecting on: rate limit and transient upstream/server errors. */
function isRetryableStatus(status: number): boolean {
  return status === 429 || status === 500 || status === 502 || status === 503 || status === 504;
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

/**
 * Streams a prompt, transparently reconnecting if the connection drops before the agent finishes.
 *
 * A normal run ends with a server `done` (or `error`) event. If the stream instead ends — or the
 * fetch throws — without one, the connection was lost; we replay the request after a short backoff
 * (emitting a `reset` first so the partial answer is discarded). We do NOT reconnect once a tool
 * call has been observed in the dropped attempt, since replaying the run could repeat tool
 * side-effects; that case surfaces an error instead.
 */
export async function streamPrompt(token: string, options: StreamOptions, handlers: StreamHandlers) {
  const requestBody = JSON.stringify({
    prompt: options.prompt,
    ...(options.attachments?.length ? { attachments: options.attachments } : {}),
    ...(options.priorMessages?.length ? { priorMessages: options.priorMessages } : {}),
    ...(options.model ? { model: options.model } : {}),
    ...(options.reasoningEffort ? { reasoningEffort: options.reasoningEffort } : {}),
    sessionId: options.threadId,
    // Only sandbox sessions advertise filesystem tools; pure chats omit sandboxId entirely.
    ...(options.sandboxId ? { sandboxId: options.sandboxId } : {})
  });

  const maxAttempts = STREAM_RECONNECT_DELAYS_MS.length;
  let lastError: (Error & { status?: number }) | null = null;

  for (let attempt = 0; attempt <= maxAttempts; attempt++) {
    if (attempt > 0) {
      // Discard whatever the failed attempt streamed so the replay renders cleanly.
      handlers.onEvent({ type: "reset" });
      await delay(STREAM_RECONNECT_DELAYS_MS[attempt - 1]);
    }

    let receivedTerminal = false;
    let executedTool = false;

    try {
      const response = await fetch("/api/agent/stream", {
        method: "POST",
        headers: {
          Accept: "text/event-stream",
          "Content-Type": "application/json",
          Authorization: `Bearer ${token}`
        },
        body: requestBody
      });

      if (!response.ok) {
        const error = await errorFromResponse(response);
        // Transient server-side failures are worth replaying; anything else is final.
        if (isRetryableStatus(response.status) && attempt < maxAttempts) {
          lastError = error;
          continue;
        }
        throw error;
      }

      if (!response.body) {
        throw new Error("Stream body was not available.");
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";

      const dispatch = (event: StreamEvent | null) => {
        if (!event) return;
        if (event.type === "tool") executedTool = true;
        if (event.type === "done" || event.type === "error") receivedTerminal = true;
        handlers.onEvent(event);
      };

      while (!receivedTerminal) {
        const { done, value } = await reader.read();
        if (done) break;

        buffer += decoder.decode(value, { stream: true });
        const parts = buffer.split("\n\n");
        buffer = parts.pop() ?? "";

        for (const rawEvent of parts) {
          dispatch(parseSseEvent(rawEvent));
        }
      }

      if (!receivedTerminal) {
        buffer += decoder.decode();
        dispatch(parseSseEvent(buffer));
      }

      if (receivedTerminal) return;

      // Stream ended without a terminal event: the connection dropped mid-run.
      lastError = new Error("Connection lost before the response completed.");
      if (executedTool || attempt >= maxAttempts) {
        const recovered = await recoverAssistantAnswer(
          token,
          options.threadId,
          options.priorMessages?.length ?? 0,
          options.prompt
        );
        if (recovered) {
          handlers.onEvent({ type: "replace", content: recovered });
          handlers.onEvent({ type: "done" });
          return;
        }
        handlers.onEvent({ type: "error", message: lastError.message });
        return;
      }
      // Fall through to the next attempt.
    } catch (e) {
      const error = e as Error & { status?: number };
      lastError = error;
      // A tool already ran, or retries are exhausted: don't replay, just report.
      if (executedTool || attempt >= maxAttempts) {
        throw error;
      }
      // Otherwise loop and reconnect.
    }
  }

  if (lastError) throw lastError;
}

type ServerChatMessage = {
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

async function recoverAssistantAnswer(
  token: string,
  sessionId: string,
  priorMessageCount: number,
  prompt: string
): Promise<string | null> {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      const history = await fetchConversationHistory(token, sessionId);
      if (history.length >= priorMessageCount + 2) {
        const lastUser = history.at(-2);
        const lastAssistant = history.at(-1);
        if (lastUser?.role === "user"
          && lastAssistant?.role === "assistant"
          && (lastUser.content ?? "") === prompt
          && (lastAssistant.content ?? "").trim()) {
          return lastAssistant.content;
        }
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

    if (message.role === "tool") {
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
  }

  return {
    ...thread,
    updatedAt: nowIso(),
    entries
  };
}

export async function createSandbox(token: string): Promise<SandboxInfo> {
  return apiFetch<SandboxInfo>("/api/sandboxes", { method: "POST", body: JSON.stringify({}) }, token);
}

export async function createGitHubSandbox(
  token: string,
  repoFullName: string,
  branch: string
): Promise<SandboxInfo> {
  return apiFetch<SandboxInfo>(
    "/api/sandboxes/github",
    { method: "POST", body: JSON.stringify({ repoFullName, branch }) },
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
