// @vitest-environment jsdom

import { beforeEach, describe, expect, it } from "vitest";

import { loadAuthSession, saveAuthSession } from "./apiClient";

const AUTH_STORAGE_KEY = "bouw-auth-session-v1";

describe("auth session storage", () => {
  beforeEach(() => {
    const local = new Map<string, string>();
    const session = new Map<string, string>();
    Object.defineProperty(window, "localStorage", {
      value: {
        getItem: (key: string) => local.get(key) ?? null,
        setItem: (key: string, value: string) => void local.set(key, value),
        removeItem: (key: string) => void local.delete(key),
        clear: () => void local.clear()
      },
      configurable: true
    });
    Object.defineProperty(window, "sessionStorage", {
      value: {
        getItem: (key: string) => session.get(key) ?? null,
        setItem: (key: string, value: string) => void session.set(key, value),
        removeItem: (key: string) => void session.delete(key),
        clear: () => void session.clear()
      },
      configurable: true
    });
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it("persists sessions in localStorage", () => {
    saveAuthSession({
      token: "token-1",
      username: "user@example.com",
      roles: ["ROLE_USER"],
      expiresAt: "2026-07-26T00:00:00Z"
    });

    expect(JSON.parse(window.localStorage.getItem(AUTH_STORAGE_KEY) ?? "{}")).toMatchObject({
      token: "token-1",
      username: "user@example.com"
    });
    expect(window.sessionStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });

  it("migrates an older sessionStorage session into localStorage", () => {
    window.sessionStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify({
      token: "token-2",
      username: "user@example.com",
      roles: ["ROLE_USER"],
      expiresAt: "2026-07-26T00:00:00Z"
    }));

    const restored = loadAuthSession();

    expect(restored?.token).toBe("token-2");
    expect(window.localStorage.getItem(AUTH_STORAGE_KEY)).not.toBeNull();
    expect(window.sessionStorage.getItem(AUTH_STORAGE_KEY)).toBeNull();
  });
});
