import { useState } from "react";

import { AppHeader } from "../components/AppHeader";
import { requestPasswordReset, confirmPasswordReset } from "../services/apiClient";
import type { AuthSession } from "../lib/types";

/**
 * Verified password reset. The user enters a new password, which is held server-side while a 6-digit
 * code is emailed to them; the new password is only persisted once that code is confirmed — the same
 * email-verification gate used by sign-in.
 */
export function PasswordResetScreen(props: {
  session: AuthSession;
  onBack: () => void;
  onMenu: () => void;
}) {
  const { session, onBack, onMenu } = props;

  const [phase, setPhase] = useState<"enter" | "verify">("enter");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [code, setCode] = useState("");
  const [pendingEmail, setPendingEmail] = useState("");

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const handleSendCode = async () => {
    if (busy) return;
    setError(null);
    setNotice(null);
    if (newPassword.length < 8) {
      setError("New password must be at least 8 characters.");
      return;
    }
    if (newPassword !== confirmPassword) {
      setError("New passwords do not match.");
      return;
    }
    setBusy(true);
    try {
      const challenge = await requestPasswordReset(session.token, newPassword);
      setPendingEmail(challenge.email);
      setNotice(challenge.message);
      setPhase("verify");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not start the password reset.");
    } finally {
      setBusy(false);
    }
  };

  const handleVerify = async () => {
    if (busy) return;
    setError(null);
    if (code.trim().length !== 6) {
      setError("Enter the 6-digit code we emailed you.");
      return;
    }
    setBusy(true);
    try {
      await confirmPasswordReset(session.token, code.trim());
      setDone(true);
      setNotice(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not verify the code.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="settings-body">
      <AppHeader backAction={{ onClick: onBack }} title="Reset password" onMenu={onMenu} />

      {done ? (
        <div className="settings-section">
          <p className="screen-note">Your password has been updated.</p>
          <div className="settings-section settings-actions">
            <button type="button" className="primary-button" onClick={onBack}>
              Done
            </button>
          </div>
        </div>
      ) : phase === "enter" ? (
        <>
          <div className="settings-section">
            <div className="history-group-label">NEW PASSWORD</div>
            <p className="settings-hint">
              Choose a new password. We&apos;ll email a 6-digit verification code to confirm it&apos;s you
              before the change is saved.
            </p>

            <label className="composer-select settings-select">
              <span>New password</span>
              <input
                type="password"
                placeholder="Min 8 characters"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                autoComplete="new-password"
                className="settings-number-input"
              />
            </label>

            <label className="composer-select settings-select">
              <span>Confirm new password</span>
              <input
                type="password"
                placeholder="Repeat new password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                autoComplete="new-password"
                onKeyDown={(e) => {
                  if (e.key === "Enter") void handleSendCode();
                }}
                className="settings-number-input"
              />
            </label>
          </div>

          {error && <p className="login-error">{error}</p>}

          <div className="settings-section settings-actions">
            <button type="button" className="primary-button" onClick={handleSendCode} disabled={busy}>
              {busy ? "Sending…" : "Send verification code"}
            </button>
          </div>
        </>
      ) : (
        <>
          <div className="settings-section">
            <div className="history-group-label">VERIFY</div>
            <p className="settings-hint">
              Enter the 6-digit code we emailed{pendingEmail ? ` to ${pendingEmail}` : ""} to save your new
              password.
            </p>

            <label className="composer-select settings-select">
              <span>Verification code</span>
              <input
                type="text"
                inputMode="numeric"
                placeholder="123456"
                maxLength={6}
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                autoComplete="one-time-code"
                onKeyDown={(e) => {
                  if (e.key === "Enter") void handleVerify();
                }}
                className="settings-number-input"
              />
            </label>
          </div>

          {notice && <p className="screen-note">{notice}</p>}
          {error && <p className="login-error">{error}</p>}

          <div className="settings-section settings-actions">
            <button type="button" className="primary-button" onClick={handleVerify} disabled={busy}>
              {busy ? "Verifying…" : "Verify & update password"}
            </button>
            <button
              type="button"
              className="secondary-button"
              onClick={() => {
                setPhase("enter");
                setCode("");
                setError(null);
                setNotice(null);
              }}
              disabled={busy}
            >
              Back
            </button>
          </div>
        </>
      )}
    </div>
  );
}
