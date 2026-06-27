import { KeyRound, Lock, Mail } from "lucide-react";

import type { AuthMode } from "../hooks/useAuthBootstrap";
import { COLORS } from "../lib/theme";

const LOGO = "/bouw-bird.jpg";

function Field(props: {
  icon: typeof Mail;
  label: string;
  value: string;
  type?: string;
  placeholder: string;
  name?: string;
  autoComplete?: string;
  inputMode?: "text" | "numeric";
  maxLength?: number;
  onChange: (value: string) => void;
}) {
  const {
    icon: Icon,
    label,
    value,
    type = "text",
    placeholder,
    name,
    autoComplete,
    inputMode,
    maxLength,
    onChange
  } = props;

  return (
    <label className="login-field">
      <span>{label}</span>
      <div className="login-input">
        <Icon size={18} strokeWidth={2} color={COLORS.faint} />
        <input
          type={type}
          value={value}
          name={name}
          autoComplete={autoComplete}
          inputMode={inputMode}
          maxLength={maxLength}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
        />
      </div>
    </label>
  );
}

export function LoginScreen(props: {
  mode: AuthMode;
  email: string;
  password: string;
  confirmPassword: string;
  code: string;
  pendingEmail: string;
  notice: string | null;
  error: string | null;
  busy: boolean;
  onEmail: (value: string) => void;
  onPassword: (value: string) => void;
  onConfirmPassword: (value: string) => void;
  onCode: (value: string) => void;
  onSwitchMode: (mode: AuthMode) => void;
  onSubmitCredentials: () => void;
  onSubmitCode: () => void;
  onCancelVerification: () => void;
}) {
  const {
    mode,
    email,
    password,
    confirmPassword,
    code,
    pendingEmail,
    notice,
    error,
    busy,
    onEmail,
    onPassword,
    onConfirmPassword,
    onCode,
    onSwitchMode,
    onSubmitCredentials,
    onSubmitCode,
    onCancelVerification
  } = props;

  if (mode === "verify" || mode === "forgot-verify") {
    const isForgot = mode === "forgot-verify";
    const ready = code.trim().length === 6 && !busy;
    return (
      <form
        className="login-screen"
        onSubmit={(event) => {
          event.preventDefault();
          if (ready) onSubmitCode();
        }}
      >
        <div className="login-brand">
          <img src={LOGO} alt="Bouw" className="login-logo" />
          <span className="login-wordmark">BOUW</span>
          <p>
            {isForgot ? "Enter the code we emailed to reset your password" : "Enter the code we emailed"}
            {pendingEmail ? ` to ${pendingEmail}` : ""}
          </p>
        </div>

        <div className="login-fields">
          {/* Carry the email so password managers associate the saved credential with this account. */}
          <input type="hidden" name="username" autoComplete="username" value={pendingEmail} readOnly />
          <Field
            icon={KeyRound}
            label="Verification code"
            value={code}
            name="otp"
            autoComplete="one-time-code"
            inputMode="numeric"
            maxLength={6}
            placeholder="123456"
            onChange={(value) => onCode(value.replace(/\D/g, "").slice(0, 6))}
          />
        </div>

        {notice ? <p className="screen-note">{notice}</p> : null}
        {error ? <p className="login-error">{error}</p> : null}

        <button type="submit" className="signin-button" disabled={!ready}>
          {busy ? "Verifying…" : isForgot ? "Verify & reset password" : "Verify & continue"}
        </button>

        <button type="button" className="login-link" onClick={onCancelVerification} disabled={busy}>
          Back to sign in
        </button>
      </form>
    );
  }

  const isRegister = mode === "register";
  const isForgot = mode === "forgot";
  const needsConfirm = isRegister || isForgot;
  const credentialsReady =
    Boolean(email.trim() && password.trim()) &&
    (!needsConfirm || Boolean(confirmPassword.trim())) &&
    !busy;

  const subtitle = isRegister
    ? "Create your account"
    : isForgot
      ? "Reset your password"
      : "Sign in to your workspace";

  const passwordLabel = isForgot ? "New password" : "Password";
  // Tell the browser/password manager whether this is an existing password (offer to fill on sign-in)
  // or a new one (offer to save the new value after register/reset).
  const passwordAutoComplete = isRegister || isForgot ? "new-password" : "current-password";
  const passwordPlaceholder = isRegister
    ? "At least 8 characters"
    : isForgot
      ? "At least 8 characters"
      : "Enter your password";

  const submitLabel = isRegister ? "Create account" : isForgot ? "Send verification code" : "Sign in";

  return (
    <form
      className="login-screen"
      onSubmit={(event) => {
        event.preventDefault();
        if (credentialsReady) onSubmitCredentials();
      }}
    >
      <div className="login-brand">
        <img src={LOGO} alt="Bouw" className="login-logo" />
        <span className="login-wordmark">BOUW</span>
        <p>{subtitle}</p>
      </div>

      <div className="login-fields">
        <Field
          icon={Mail}
          label="Email"
          type="email"
          value={email}
          name="username"
          autoComplete="username"
          placeholder="you@example.com"
          onChange={onEmail}
        />
        <Field
          icon={Lock}
          label={passwordLabel}
          type="password"
          value={password}
          name="password"
          autoComplete={passwordAutoComplete}
          placeholder={passwordPlaceholder}
          onChange={onPassword}
        />
        {needsConfirm ? (
          <Field
            icon={Lock}
            label="Confirm password"
            type="password"
            value={confirmPassword}
            name="confirm-password"
            autoComplete="new-password"
            placeholder="Re-enter your password"
            onChange={onConfirmPassword}
          />
        ) : null}
      </div>

      {error ? <p className="login-error">{error}</p> : null}

      <button type="submit" className="signin-button" disabled={!credentialsReady}>
        {busy ? "Please wait…" : submitLabel}
      </button>

      {isForgot ? (
        <button type="button" className="login-link" onClick={() => onSwitchMode("login")} disabled={busy}>
          Back to sign in
        </button>
      ) : (
        <>
          {!isRegister ? (
            <button
              type="button"
              className="login-link"
              onClick={() => onSwitchMode("forgot")}
              disabled={busy}
            >
              Forgot your password?
            </button>
          ) : null}
          <button
            type="button"
            className="login-link"
            onClick={() => onSwitchMode(isRegister ? "login" : "register")}
            disabled={busy}
          >
            {isRegister ? "Already have an account? Sign in" : "Create an account"}
          </button>
        </>
      )}
    </form>
  );
}
