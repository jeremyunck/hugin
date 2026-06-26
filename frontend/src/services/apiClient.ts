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
  displayName?: string | null;
  email?: string | null;
  customInstructions?: string | null;
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
  // Mock mode short-circuits every backend call to fixture data. The mock module is dynamically
  // imported behind the build-time guard so neither it nor the fixtures ship in a normal build.
  if (import.meta.env.VITE_HUGIN_MOCK_MODE === "true") {
    const { mockApiFetch } = await import("../mocks/mockApiFetch");
    return mockApiFetch<T>(path, init);
  }

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

type AuthChallengeResponse = {
  email: string;
  verificationRequired: boolean;
  message: string;
};

/** Step 1 of login: validate the password and trigger the emailed 6-digit verification code. */
export async function requestLogin(email: string, password: string): Promise<AuthChallengeResponse> {
  return apiFetch<AuthChallengeResponse>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password })
  });
}

/** Step 1 of sign-up: register an email/password and trigger the emailed verification code. */
export async function requestRegister(
  email: string,
  password: string,
  confirmPassword: string
): Promise<AuthChallengeResponse> {
  return apiFetch<AuthChallengeResponse>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({ email, password, confirmPassword })
  });
}

/** Step 2 (shared by login + sign-up): confirm the code to create/authenticate and get a session. */
export async function verifyCode(email: string, code: string): Promise<AuthSession> {
  const response = await apiFetch<AuthLoginResponse>("/api/auth/verify", {
    method: "POST",
    body: JSON.stringify({ email, code })
  });

  return {
    token: response.token,
    username: response.username,
    roles: response.roles,
    expiresAt: response.expiresAt
  };
}

/**
 * Step 1 of the forgotten-password flow (login screen, no session): submit the account email and a
 * new password to trigger an emailed verification code. The response message is generic so it never
 * reveals whether the email is registered.
 */
export async function requestForgotPassword(
  email: string,
  newPassword: string,
  confirmPassword: string
): Promise<AuthChallengeResponse> {
  return apiFetch<AuthChallengeResponse>("/api/auth/password/forgot", {
    method: "POST",
    body: JSON.stringify({ email, newPassword, confirmPassword })
  });
}

/** Step 2 of the forgotten-password flow: confirm the code, persist the new password, and sign in. */
export async function confirmForgotPassword(email: string, code: string): Promise<AuthSession> {
  const response = await apiFetch<AuthLoginResponse>("/api/auth/password/forgot/verify", {
    method: "POST",
    body: JSON.stringify({ email, code })
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
    expiresAt: response.expiresAt,
    displayName: response.displayName,
    email: response.email,
    customInstructions: response.customInstructions
  };
}

export type UserProfile = {
  username: string;
  displayName: string | null;
  email: string | null;
  customInstructions: string | null;
};

export async function fetchUserProfile(token: string): Promise<UserProfile> {
  return apiFetch<UserProfile>("/api/user/profile", {}, token);
}

export async function updateUserProfile(
  token: string,
  profile: { displayName: string | null; email: string | null; customInstructions: string | null }
): Promise<UserProfile> {
  return apiFetch<UserProfile>("/api/user/profile", { method: "PUT", body: JSON.stringify(profile) }, token);
}

export type PasswordResetChallenge = {
  email: string;
  verificationRequired: boolean;
  message: string;
};

/** Step 1 of a verified password reset: validate the new password and email a verification code. */
export async function requestPasswordReset(
  token: string,
  newPassword: string
): Promise<PasswordResetChallenge> {
  return apiFetch<PasswordResetChallenge>("/api/user/password/reset/request", {
    method: "POST",
    body: JSON.stringify({ newPassword })
  }, token);
}

/** Step 2 of a verified password reset: confirm the emailed code and persist the new password. */
export async function confirmPasswordReset(token: string, code: string): Promise<void> {
  await apiFetch<void>("/api/user/password/reset/confirm", {
    method: "POST",
    body: JSON.stringify({ code })
  }, token);
}

export type OpenRouterKeyStatus = {
  configured: boolean;
  last4: string | null;
};

export type OpenRouterCredits = {
  configured: boolean;
  totalCredits: number | null;
  totalUsage: number | null;
  remaining: number | null;
  error: string | null;
};

export async function fetchOpenRouterKeyStatus(token: string): Promise<OpenRouterKeyStatus> {
  return apiFetch<OpenRouterKeyStatus>("/api/user/openrouter-key", {}, token);
}

export async function saveOpenRouterKey(token: string, apiKey: string): Promise<OpenRouterKeyStatus> {
  return apiFetch<OpenRouterKeyStatus>("/api/user/openrouter-key", {
    method: "PUT",
    body: JSON.stringify({ apiKey })
  }, token);
}

export async function deleteOpenRouterKey(token: string): Promise<OpenRouterKeyStatus> {
  return apiFetch<OpenRouterKeyStatus>("/api/user/openrouter-key", { method: "DELETE" }, token);
}

export async function fetchOpenRouterCredits(token: string): Promise<OpenRouterCredits> {
  return apiFetch<OpenRouterCredits>("/api/user/openrouter-credits", {}, token);
}
