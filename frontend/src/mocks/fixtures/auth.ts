import type { AuthSession } from "../../lib/types";
import type {
  OpenRouterCredits,
  OpenRouterKeyStatus,
  UserProfile
} from "../../services/apiClient";

/**
 * A frozen "now" so every fixture — relative timestamps, credit usage, run start times — renders
 * identically on every run. Stabilizing time here (rather than in components) keeps screenshots
 * deterministic without touching production code.
 */
export const MOCK_NOW = "2025-11-20T17:30:00.000Z";

/** Minutes-ago helper for building realistic, but fixed, relative timestamps. */
export function minutesAgo(minutes: number): string {
  return new Date(Date.parse(MOCK_NOW) - minutes * 60_000).toISOString();
}

export function daysAgo(days: number): string {
  return new Date(Date.parse(MOCK_NOW) - days * 24 * 60 * 60_000).toISOString();
}

/** The signed-in demo identity. No real account, token, or credentials — purely illustrative. */
export const mockSession: AuthSession = {
  token: "mock-session-token",
  username: "ada@bouw.dev",
  roles: ["ROLE_USER"],
  expiresAt: daysAgo(-7),
  displayName: "Ada Lovelace",
  email: "ada@bouw.dev",
  customInstructions:
    "Prefer concise answers. Use British English. When writing code, favour small, well-named functions and add a short comment explaining any non-obvious decision."
};

/** Shape returned by `GET /api/auth/me`. */
export const mockAuthMe = {
  username: mockSession.username,
  roles: mockSession.roles,
  issuedAt: daysAgo(1),
  expiresAt: mockSession.expiresAt,
  displayName: mockSession.displayName,
  email: mockSession.email,
  customInstructions: mockSession.customInstructions
};

export const mockUserProfile: UserProfile = {
  username: mockSession.username,
  displayName: mockSession.displayName ?? null,
  email: mockSession.email ?? null,
  customInstructions: mockSession.customInstructions ?? null
};

export const mockOpenRouterKeyStatus: OpenRouterKeyStatus = {
  configured: true,
  last4: "9f2c"
};

export const mockOpenRouterCredits: OpenRouterCredits = {
  configured: true,
  totalCredits: 50,
  totalUsage: 12.47,
  remaining: 37.53,
  error: null
};
