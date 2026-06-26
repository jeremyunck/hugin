import { useCallback, useEffect, useState } from "react";

import type { AuthSession } from "../lib/types";
import {
  fetchCurrentUser,
  loadAuthSession,
  requestLogin,
  requestRegister,
  saveAuthSession,
  verifyCode
} from "../services/apiClient";

/** Which step of the email + password + code flow the login surface is showing. */
export type AuthMode = "login" | "register" | "verify";

/**
 * Owns auth/session startup and the email-based login surface. On mount it validates any stored
 * session so a refresh keeps the user signed in. Both signing in and creating an account are
 * two-step: submitting credentials emails a 6-digit code ({@code submitCredentials}), and confirming
 * that code ({@code submitCode}) creates the account (for sign-up) and establishes the session. The
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
  const [mode, setMode] = useState<AuthMode>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [code, setCode] = useState("");
  const [pendingEmail, setPendingEmail] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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

  /** Switch between the sign-in and create-account forms, clearing any transient state. */
  const switchMode = useCallback((next: AuthMode) => {
    setMode(next);
    setError(null);
    setNotice(null);
    setCode("");
    setConfirmPassword("");
  }, []);

  /** Step 1: submit email/password to either log in or register, which emails a verification code. */
  const submitCredentials = useCallback(async () => {
    if (busy) return;
    const trimmedEmail = email.trim();
    if (!trimmedEmail || !password.trim()) return;
    if (mode === "register" && password !== confirmPassword) {
      setError("Passwords do not match.");
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const challenge =
        mode === "register"
          ? await requestRegister(trimmedEmail, password, confirmPassword)
          : await requestLogin(trimmedEmail, password);
      setPendingEmail(challenge.email || trimmedEmail);
      setNotice(challenge.message);
      setCode("");
      setMode("verify");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Something went wrong.");
    } finally {
      setBusy(false);
    }
  }, [busy, email, password, confirmPassword, mode]);

  /** Step 2: confirm the emailed code to finish creating/authenticating the account. */
  const submitCode = useCallback(async () => {
    if (busy || !code.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const session = await verifyCode(pendingEmail, code.trim());
      // Fetch the full profile (display name, email, custom instructions) right after verification.
      const validated = await fetchCurrentUser(session.token).catch(() => session);
      saveAuthSession(validated);
      onSignedIn(validated);
      setSession(validated);
      setPassword("");
      setConfirmPassword("");
      setCode("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Verification failed.");
    } finally {
      setBusy(false);
    }
  }, [busy, code, pendingEmail, onSignedIn, setSession]);

  /** Abandon the code step and return to the sign-in form. */
  const cancelVerification = useCallback(() => {
    setMode("login");
    setCode("");
    setError(null);
    setNotice(null);
  }, []);

  const signOut = useCallback(() => {
    // Clear the stored session and form, then let the caller reset app-level UI state and re-allow
    // bootstrap so a subsequent sign-in re-activates a thread.
    saveAuthSession(null);
    setSession(null);
    setMode("login");
    setEmail("");
    setPassword("");
    setConfirmPassword("");
    setCode("");
    setPendingEmail("");
    setNotice(null);
    setError(null);
    onSignedOut();
  }, [setSession, onSignedOut]);

  return {
    booting,
    mode,
    email,
    password,
    confirmPassword,
    code,
    pendingEmail,
    notice,
    error,
    busy,
    setEmail,
    setPassword,
    setConfirmPassword,
    setCode,
    switchMode,
    submitCredentials,
    submitCode,
    cancelVerification,
    signOut
  };
}
