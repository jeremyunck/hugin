import type { ButtonHTMLAttributes, HTMLAttributes, InputHTMLAttributes, ReactNode, TextareaHTMLAttributes } from "react";
import { ChevronRight } from "lucide-react";

export function Button({
  className = "",
  variant = "solid",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: "solid" | "ghost" | "danger" | "icon" }) {
  return <button className={`ui-button ${variant} ${className}`.trim()} {...props} />;
}

export function Card({
  className = "",
  children,
  ...props
}: HTMLAttributes<HTMLElement> & { className?: string; children: ReactNode }) {
  return (
    <section className={`ui-card ${className}`.trim()} {...props}>
      {children}
    </section>
  );
}

export function Field({
  label,
  hint,
  className = "",
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { label: string; hint?: string }) {
  return (
    <label className={`ui-field ${className}`.trim()}>
      <span>{label}</span>
      <input {...props} />
      {hint ? <small>{hint}</small> : null}
    </label>
  );
}

export function TextareaField({
  label,
  hint,
  className = "",
  ...props
}: TextareaHTMLAttributes<HTMLTextAreaElement> & { label: string; hint?: string }) {
  return (
    <label className={`ui-field ${className}`.trim()}>
      <span>{label}</span>
      <textarea {...props} />
      {hint ? <small>{hint}</small> : null}
    </label>
  );
}

export function NavRow({
  title,
  subtitle,
  icon,
  onClick,
  active = false
}: {
  title: string;
  subtitle?: string;
  icon?: ReactNode;
  onClick: () => void;
  active?: boolean;
}) {
  return (
    <button className={`nav-row ${active ? "active" : ""}`} onClick={onClick}>
      {icon ? <span className="nav-row-icon">{icon}</span> : null}
      <div className="nav-row-text">
        <div className="nav-row-title">{title}</div>
        {subtitle ? <div className="nav-row-subtitle">{subtitle}</div> : null}
      </div>
      <ChevronRight size={16} />
    </button>
  );
}

export function StatusPill({ label, tone }: { label: string; tone: "green" | "amber" | "gray" }) {
  return <span className={`status-pill ${tone}`}>{label}</span>;
}
