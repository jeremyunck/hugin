import { useCallback, useEffect, useState } from "react";

import type { AuthSession } from "../lib/types";
import {
  fetchCurrentUser,
  loadAuthSession,
  login as loginRequest,
  saveAuthSession
} from "../services/apiClient";

/**
 * Owns auth/session startup and the login form. On mount it validates any stored session so a
 * refresh keeps the user signed in; {@code signIn} authenticates and persists a new session. The
 * authenticated session is published via {@code setSession} (the chat store keys off it), and the
 * caller wires post-auth side effects through the {@code onSignedIn} / {@code onSessionRestored}
 * callbacks so this hook stays focused on identity.
 */
export function useAuthBootstrap(params: {
  setSession: (session: AuthSession | null) => void;
  onSignedIn: (session: AuthSession) => void;
  onSessionRestored: (session: AuthSession) => void;
  onSignedOut: () => void;
}) {
  const { setSession, onSignedIn, onSessionRestored, onSignedOut } = params;

  const [booting, setBooting] = useState(true);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [loginError, setLoginError] = useState<string | null>(null);
  const [signingIn, setSigningIn] = useState(false);

  useEffect(() => {
    const existing = loadAuthSession();
    if (!existing) {
      setBooting(false);
      return;
    }
    fetchCurrentUser(existing.token)
      .then((validated) => {
        saveAuthSession(validated);
        setSession(validated);
        onSessionRestored(validated);
      })
      .catch(() => saveAuthSession(null))
      .finally(() => setBooting(false));
    // Runs once on mount; callback identities are intentionally not dependencies.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const signIn = useCallback(async () => {
    if (!username.trim() || !password.trim() || signingIn) return;
    setSigningIn(true);
    setLoginError(null);
    try {
      const validated = await loginRequest(username.trim(), password);
      saveAuthSession(validated);
      onSignedIn(validated);
      setSession(validated);
      setPassword("");
    } catch (e) {
      setLoginError(e instanceof Error ? e.message : "Sign in failed.");
    } finally {
      setSigningIn(false);
    }
  }, [username, password, signingIn, onSignedIn, setSession]);

  const signOut = useCallback(() => {
    // Clear the stored session and form, then let the caller reset app-level UI state and re-allow
    // bootstrap so a subsequent sign-in re-activates a thread.
    saveAuthSession(null);
    setSession(null);
    setUsername("");
    setPassword("");
    setLoginError(null);
    onSignedOut();
  }, [setSession, onSignedOut]);

  return {
    booting,
    username,
    password,
    loginError,
    signingIn,
    setUsername,
    setPassword,
    signIn,
    signOut
  };
}
