import { KeyRound, Lock, Mail } from "lucide-react";

import type { AuthMode } from "../hooks/useAuthBootstrap";
import { COLORS } from "../lib/theme";

const LOGO = "/hugin-bird.jpg";

function Field(props: {
  icon: typeof Mail;
  label: string;
  value: string;
  type?: string;
  placeholder: string;
  inputMode?: "text" | "numeric";
  maxLength?: number;
  onChange: (value: string) => void;
  onEnter?: () => void;
}) {
  const { icon: Icon, label, value, type = "text", placeholder, inputMode, maxLength, onChange, onEnter } = props;

  return (
    <label className="login-field">
      <span>{label}</span>
      <div className="login-input">
        <Icon size={18} strokeWidth={2} color={COLORS.faint} />
        <input
          type={type}
          value={value}
          inputMode={inputMode}
          maxLength={maxLength}
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") onEnter?.();
          }}
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

  if (mode === "verify") {
    const ready = code.trim().length === 6 && !busy;
    return (
      <div className="login-screen">
        <div className="login-brand">
          <img src={LOGO} alt="Hugin" className="login-logo" />
          <span className="login-wordmark">HUGIN</span>
          <p>Enter the code we emailed{pendingEmail ? ` to ${pendingEmail}` : ""}</p>
        </div>

        <div className="login-fields">
          <Field
            icon={KeyRound}
            label="Verification code"
            value={code}
            inputMode="numeric"
            maxLength={6}
            placeholder="123456"
            onChange={(value) => onCode(value.replace(/\D/g, "").slice(0, 6))}
            onEnter={() => ready && onSubmitCode()}
          />
        </div>

        {notice ? <p className="screen-note">{notice}</p> : null}
        {error ? <p className="login-error">{error}</p> : null}

        <button type="button" className="signin-button" disabled={!ready} onClick={() => ready && onSubmitCode()}>
          {busy ? "Verifying…" : "Verify & continue"}
        </button>

        <button type="button" className="login-link" onClick={onCancelVerification} disabled={busy}>
          Back to sign in
        </button>
      </div>
    );
  }

  const isRegister = mode === "register";
  const credentialsReady =
    Boolean(email.trim() && password.trim()) &&
    (!isRegister || Boolean(confirmPassword.trim())) &&
    !busy;

  return (
    <div className="login-screen">
      <div className="login-brand">
        <img src={LOGO} alt="Hugin" className="login-logo" />
        <span className="login-wordmark">HUGIN</span>
        <p>{isRegister ? "Create your account" : "Sign in to your workspace"}</p>
      </div>

      <div className="login-fields">
        <Field
          icon={Mail}
          label="Email"
          type="email"
          value={email}
          placeholder="you@example.com"
          onChange={onEmail}
          onEnter={() => credentialsReady && onSubmitCredentials()}
        />
        <Field
          icon={Lock}
          label="Password"
          type="password"
          value={password}
          placeholder={isRegister ? "At least 8 characters" : "Enter your password"}
          onChange={onPassword}
          onEnter={() => credentialsReady && onSubmitCredentials()}
        />
        {isRegister ? (
          <Field
            icon={Lock}
            label="Confirm password"
            type="password"
            value={confirmPassword}
            placeholder="Re-enter your password"
            onChange={onConfirmPassword}
            onEnter={() => credentialsReady && onSubmitCredentials()}
          />
        ) : null}
      </div>

      {error ? <p className="login-error">{error}</p> : null}

      <button
        type="button"
        className="signin-button"
        disabled={!credentialsReady}
        onClick={() => credentialsReady && onSubmitCredentials()}
      >
        {busy ? "Please wait…" : isRegister ? "Create account" : "Sign in"}
      </button>

      <button
        type="button"
        className="login-link"
        onClick={() => onSwitchMode(isRegister ? "login" : "register")}
        disabled={busy}
      >
        {isRegister ? "Already have an account? Sign in" : "Create an account"}
      </button>
    </div>
  );
}
