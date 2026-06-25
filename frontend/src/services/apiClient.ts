import type { AuthSession } from "../lib/types";

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

/**
 * A single persisted chat event. The ordered backend event log is the source of truth for chat
 * transcripts; the UI projects this stream deterministically (see {@link ../stores/chatEventReducer}).
 */
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

export function nowIso() {
  return new Date().toISOString();
}

export function uid(prefix = "id") {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return `${prefix}-${crypto.randomUUID().slice(0, 8)}`;
  }
  return `${prefix}-${Math.random().toString(16).slice(2, 10)}`;
}

export function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Generic, locale-aware timestamp formatter used across history/thread views. */
export function formatTimestamp(iso: string) {
  return new Intl.DateTimeFormat("en", {
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit"
  }).format(new Date(iso));
}

export async function errorFromResponse(response: Response): Promise<Error & { status?: number }> {
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

/** Thin JSON fetch wrapper that attaches auth + content headers and unwraps error bodies. */
export async function apiFetch<T>(path: string, init: RequestInit = {}, token?: string): Promise<T> {
  const headers = new Headers(init.headers || {});
  headers.set("Accept", "application/json");
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(path, { ...init, headers });
  if (!response.ok) {
    throw await errorFromResponse(response);
  }
  return (await response.json()) as T;
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
