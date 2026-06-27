import type { Screen } from "../lib/screen";

/**
 * Mock mode is the foundation for local UI development and the Playwright screenshot harness. It is
 * compiled in only when the app is built/served with `VITE_BOUW_MOCK_MODE=true`; in every normal
 * build `import.meta.env.VITE_BOUW_MOCK_MODE` is undefined and {@link isMockMode} returns false, so
 * production behavior is completely untouched and none of the fixture data ships.
 */
export function isMockMode(): boolean {
  return import.meta.env.VITE_BOUW_MOCK_MODE === "true";
}

/** Every screen the App shell can render, mirrored so `?mockScreen=` only accepts real screens. */
const KNOWN_SCREENS: Screen[] = [
  "login",
  "chat",
  "purechat",
  "history",
  "integrations",
  "settings",
  "preferences",
  "github-repo",
  "agent-threads",
  "user-details",
  "password-reset"
];

/**
 * Deterministic screen routing for Playwright (and manual testing): `/?mockScreen=chat` opens the
 * project chat, `/?mockScreen=integrations` the integrations list, and so on. Reuses the existing
 * {@link Screen} model rather than inventing a parallel router. Only honoured in mock mode; a normal
 * build ignores the parameter entirely.
 */
export function readMockScreen(): Screen | null {
  if (!isMockMode() || typeof window === "undefined") return null;
  const raw = new URLSearchParams(window.location.search).get("mockScreen");
  if (!raw) return null;
  return KNOWN_SCREENS.includes(raw as Screen) ? (raw as Screen) : null;
}
