import { useEffect, useState } from "react";

import { AppHeader } from "../components/AppHeader";
import {
  updateUserProfile,
  changeUserPassword,
  fetchOpenRouterKeyStatus,
  saveOpenRouterKey,
  deleteOpenRouterKey
} from "../services/apiClient";
import type { OpenRouterKeyStatus } from "../services/apiClient";
import type { AuthSession } from "../lib/types";

export function UserDetailsScreen(props: {
  session: AuthSession;
  onBack: () => void;
  onMenu: () => void;
  onSessionUpdate: (updated: Partial<AuthSession>) => void;
}) {
  const { session, onBack, onMenu, onSessionUpdate } = props;

  const [displayName, setDisplayName] = useState(session.displayName ?? "");
  const [email, setEmail] = useState(session.email ?? "");
  const [customInstructions, setCustomInstructions] = useState(session.customInstructions ?? "");

  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");

  const [apiKeyInput, setApiKeyInput] = useState("");
  const [keyStatus, setKeyStatus] = useState<OpenRouterKeyStatus | null>(null);
  const [savingKey, setSavingKey] = useState(false);
  const [keySaved, setKeySaved] = useState(false);
  const [keyError, setKeyError] = useState<string | null>(null);

  const [savingProfile, setSavingProfile] = useState(false);
  const [savingPassword, setSavingPassword] = useState(false);
  const [profileSaved, setProfileSaved] = useState(false);
  const [passwordSaved, setPasswordSaved] = useState(false);
  const [profileError, setProfileError] = useState<string | null>(null);
  const [passwordError, setPasswordError] = useState<string | null>(null);

  // Re-seed fields when the session updates after a successful save.
  useEffect(() => {
    setDisplayName(session.displayName ?? "");
    setEmail(session.email ?? "");
    setCustomInstructions(session.customInstructions ?? "");
  }, [session.displayName, session.email, session.customInstructions]);

  // Load whether an OpenRouter key is already on file (and its masked suffix).
  useEffect(() => {
    let cancelled = false;
    fetchOpenRouterKeyStatus(session.token)
      .then((status) => {
        if (!cancelled) setKeyStatus(status);
      })
      .catch(() => {
        if (!cancelled) setKeyStatus(null);
      });
    return () => {
      cancelled = true;
    };
  }, [session.token]);

  const handleSaveKey = async () => {
    if (savingKey) return;
    setKeyError(null);
    setKeySaved(false);
    if (!apiKeyInput.trim()) {
      setKeyError("Enter an API key.");
      return;
    }
    setSavingKey(true);
    try {
      const status = await saveOpenRouterKey(session.token, apiKeyInput.trim());
      setKeyStatus(status);
      setApiKeyInput("");
      setKeySaved(true);
      window.setTimeout(() => setKeySaved(false), 3000);
    } catch (e) {
      setKeyError(e instanceof Error ? e.message : "Could not save API key.");
    } finally {
      setSavingKey(false);
    }
  };

  const handleRemoveKey = async () => {
    if (savingKey) return;
    setKeyError(null);
    setKeySaved(false);
    setSavingKey(true);
    try {
      const status = await deleteOpenRouterKey(session.token);
      setKeyStatus(status);
      setApiKeyInput("");
    } catch (e) {
      setKeyError(e instanceof Error ? e.message : "Could not remove API key.");
    } finally {
      setSavingKey(false);
    }
  };

  const handleSaveProfile = async () => {
    if (savingProfile) return;
    setSavingProfile(true);
    setProfileError(null);
    setProfileSaved(false);
    try {
      const updated = await updateUserProfile(session.token, {
        displayName: displayName.trim() || null,
        email: email.trim() || null,
        customInstructions: customInstructions.trim() || null
      });
      onSessionUpdate({
        displayName: updated.displayName,
        email: updated.email,
        customInstructions: updated.customInstructions
      });
      setProfileSaved(true);
      window.setTimeout(() => setProfileSaved(false), 3000);
    } catch (e) {
      setProfileError(e instanceof Error ? e.message : "Could not save profile.");
    } finally {
      setSavingProfile(false);
    }
  };

  const handleChangePassword = async () => {
    if (savingPassword) return;
    setPasswordError(null);
    setPasswordSaved(false);
    if (!currentPassword) {
      setPasswordError("Enter your current password.");
      return;
    }
    if (!newPassword) {
      setPasswordError("Enter a new password.");
      return;
    }
    if (newPassword.length < 8) {
      setPasswordError("New password must be at least 8 characters.");
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordError("New passwords do not match.");
      return;
    }
    setSavingPassword(true);
    try {
      await changeUserPassword(session.token, currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setPasswordSaved(true);
      window.setTimeout(() => setPasswordSaved(false), 3000);
    } catch (e) {
      setPasswordError(e instanceof Error ? e.message : "Could not change password.");
    } finally {
      setSavingPassword(false);
    }
  };

  return (
    <div className="settings-body">
      <AppHeader backAction={{ onClick: onBack }} title="Account" onMenu={onMenu} />

      <div className="settings-section">
        <div className="history-group-label">PROFILE</div>

        <div className="screen-pad">
          <p className="settings-hint">Your username cannot be changed.</p>
        </div>

        <label className="composer-select settings-select">
          <span>Username</span>
          <input type="text" value={session.username} disabled readOnly className="settings-number-input" />
        </label>

        <label className="composer-select settings-select">
          <span>Display name</span>
          <input
            type="text"
            placeholder="Your name"
            value={displayName}
            onChange={(e) => setDisplayName(e.target.value)}
            maxLength={200}
            className="settings-number-input"
          />
        </label>

        <label className="composer-select settings-select">
          <span>Email</span>
          <input
            type="email"
            placeholder="you@example.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            maxLength={255}
            className="settings-number-input"
          />
        </label>
      </div>

      <div className="settings-section">
        <div className="history-group-label">CUSTOM INSTRUCTIONS</div>
        <p className="settings-hint">
          Standing instructions injected into every agent session — e.g. preferred coding style, output
          format, or domain context.
        </p>
        <textarea
          placeholder="Always respond in British English and prefer functional programming patterns..."
          value={customInstructions}
          onChange={(e) => setCustomInstructions(e.target.value)}
          rows={5}
          style={{
            width: "100%",
            border: "1px solid #e4e5e8",
            borderRadius: 12,
            padding: "10px 12px",
            fontSize: "calc(13px * var(--font-scale))",
            fontFamily: "inherit",
            resize: "vertical",
            boxSizing: "border-box",
            outline: "none"
          }}
        />
      </div>

      {profileError && <p className="login-error">{profileError}</p>}
      {profileSaved && <p className="screen-note">Profile saved.</p>}

      <div className="settings-actions">
        <button
          type="button"
          className="primary-button"
          onClick={handleSaveProfile}
          disabled={savingProfile}
        >
          {savingProfile ? "Saving…" : "Save profile"}
        </button>
      </div>

      <div className="settings-section">
        <div className="history-group-label">OPENROUTER API KEY</div>
        <p className="settings-hint">
          Your personal OpenRouter key is used to run your agent sessions and powers the usage meter.
          It is encrypted at rest and never shared with other users.
        </p>

        {keyStatus?.configured ? (
          <div className="screen-pad">
            <p className="settings-hint">
              A key is on file{keyStatus.last4 ? <> (ending in <code>••••{keyStatus.last4}</code>)</> : null}.
              Enter a new key below to replace it.
            </p>
          </div>
        ) : null}

        <label className="composer-select settings-select">
          <span>API key</span>
          <input
            type="password"
            placeholder="sk-or-v1-..."
            value={apiKeyInput}
            onChange={(e) => setApiKeyInput(e.target.value)}
            autoComplete="off"
            className="settings-number-input"
          />
        </label>

        {keyError && <p className="login-error">{keyError}</p>}
        {keySaved && <p className="screen-note">API key saved.</p>}

        <div className="settings-actions">
          <button
            type="button"
            className="primary-button"
            onClick={handleSaveKey}
            disabled={savingKey}
          >
            {savingKey ? "Saving…" : keyStatus?.configured ? "Replace key" : "Save key"}
          </button>
          {keyStatus?.configured ? (
            <button
              type="button"
              className="secondary-button"
              onClick={handleRemoveKey}
              disabled={savingKey}
            >
              Remove
            </button>
          ) : null}
        </div>
      </div>

      <div className="settings-section">
        <div className="history-group-label">CHANGE PASSWORD</div>

        <label className="composer-select settings-select">
          <span>Current password</span>
          <input
            type="password"
            placeholder="Current password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            autoComplete="current-password"
            className="settings-number-input"
          />
        </label>

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
            className="settings-number-input"
          />
        </label>
      </div>

      {passwordError && <p className="login-error">{passwordError}</p>}
      {passwordSaved && <p className="screen-note">Password changed successfully.</p>}

      <div className="settings-actions">
        <button
          type="button"
          className="primary-button"
          onClick={handleChangePassword}
          disabled={savingPassword}
        >
          {savingPassword ? "Saving…" : "Change password"}
        </button>
      </div>
    </div>
  );
}
