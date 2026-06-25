import { useEffect, useState } from "react";
import { ArrowLeft } from "lucide-react";

import type { ModelOption } from "../lib/types";
import {
  DEFAULT_REQUEST_TIMEOUT_SECONDS,
  FONT_SIZE_OPTIONS,
  MAX_TOOL_CALLS_MAX,
  MAX_TOOL_CALLS_MIN,
  REQUEST_TIMEOUT_MAX,
  REQUEST_TIMEOUT_MIN,
  normalizeMaxToolCalls,
  normalizeRequestTimeout,
  type AppPreferences,
  type FontSizeId
} from "../lib/preferences";

/**
 * General settings: font size, default chat model, research model, and agent run limits. The screen
 * edits a local draft so nothing persists until the user presses Save.
 */
export function SettingsScreen(props: {
  preferences: AppPreferences;
  enabledModels: ModelOption[];
  onBack: () => void;
  onSave: (next: AppPreferences) => void;
  onOpenModelSettings: () => void;
}) {
  const { preferences, enabledModels, onBack, onSave, onOpenModelSettings } = props;

  const [fontSize, setFontSize] = useState<FontSizeId>(preferences.fontSize);
  const [defaultModelId, setDefaultModelId] = useState<string | null>(preferences.defaultModelId);
  const [researchModelId, setResearchModelId] = useState<string | null>(preferences.researchModelId);
  const [maxToolCallsDraft, setMaxToolCallsDraft] = useState(
    preferences.maxToolCalls == null ? "" : String(preferences.maxToolCalls)
  );
  const [requestTimeoutDraft, setRequestTimeoutDraft] = useState(
    preferences.requestTimeoutSeconds == null ? "" : String(preferences.requestTimeoutSeconds)
  );
  const [justSaved, setJustSaved] = useState(false);

  // Re-seed the draft whenever the saved preferences change (e.g. after a save or an external update).
  useEffect(() => {
    setFontSize(preferences.fontSize);
    setDefaultModelId(preferences.defaultModelId);
    setResearchModelId(preferences.researchModelId);
    setMaxToolCallsDraft(preferences.maxToolCalls == null ? "" : String(preferences.maxToolCalls));
    setRequestTimeoutDraft(
      preferences.requestTimeoutSeconds == null ? "" : String(preferences.requestTimeoutSeconds)
    );
  }, [preferences]);

  const resolvedDefault = enabledModels.find((model) => model.id === defaultModelId)?.id ?? enabledModels[0]?.id ?? "";
  // The research model is optional: an empty value means "use the server default". A previously
  // chosen model that is no longer enabled collapses back to the server default.
  const resolvedResearch = enabledModels.find((model) => model.id === researchModelId)?.id ?? "";
  const normalizedMaxToolCalls = maxToolCallsDraft.trim() === "" ? null : normalizeMaxToolCalls(maxToolCallsDraft);
  const normalizedRequestTimeout = requestTimeoutDraft.trim() === "" ? null : normalizeRequestTimeout(requestTimeoutDraft);

  const dirty =
    fontSize !== preferences.fontSize
    || (resolvedDefault || null) !== preferences.defaultModelId
    || (resolvedResearch || null) !== preferences.researchModelId
    || normalizedMaxToolCalls !== preferences.maxToolCalls
    || normalizedRequestTimeout !== preferences.requestTimeoutSeconds;

  const handleSave = () => {
    onSave({
      fontSize,
      defaultModelId: resolvedDefault || null,
      researchModelId: resolvedResearch || null,
      maxToolCalls: normalizedMaxToolCalls,
      requestTimeoutSeconds: normalizedRequestTimeout
    });
    setJustSaved(true);
    window.setTimeout(() => setJustSaved(false), 2000);
  };

  const maxToolCallsCurrent =
    preferences.maxToolCalls == null ? "Server default" : `${preferences.maxToolCalls} per message`;
  const requestTimeoutCurrent =
    preferences.requestTimeoutSeconds == null
      ? `Server default (${DEFAULT_REQUEST_TIMEOUT_SECONDS}s)`
      : `${preferences.requestTimeoutSeconds}s`;

  return (
    <div className="settings-body">
      <div className="back-row">
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">Settings</h1>
        <p className="integration-subtitle">Personalize how Hugin looks and how the agent runs. Changes apply when you press Save.</p>
      </div>

      <div className="settings-section">
        <div className="history-group-label">FONT SIZE</div>
        <p className="settings-hint">Adjusts the text size across the entire app.</p>
        <div className="font-size-options" role="group" aria-label="Font size">
          {FONT_SIZE_OPTIONS.map((option) => (
            <button
              key={option.id}
              type="button"
              className={`font-size-option ${fontSize === option.id ? "font-size-option-active" : ""}`}
              aria-pressed={fontSize === option.id}
              onClick={() => setFontSize(option.id)}
            >
              <span className="font-size-preview" style={{ fontSize: `${option.scale}rem` }}>
                Aa
              </span>
              <span className="font-size-label">{option.label}</span>
            </button>
          ))}
        </div>
      </div>

      <div className="settings-section">
        <div className="history-group-label">DEFAULT MODEL</div>
        <p className="settings-hint">New chats start with this model. Choose from the models you have enabled.</p>
        {enabledModels.length === 0 ? (
          <p className="history-empty">
            No models are enabled yet.{" "}
            <button type="button" className="link-button" onClick={onOpenModelSettings}>
              Enable a model
            </button>{" "}
            to set a default.
          </p>
        ) : (
          <label className="composer-select settings-select">
            <span>Model</span>
            <select value={resolvedDefault} onChange={(event) => setDefaultModelId(event.target.value)}>
              {enabledModels.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.name}
                </option>
              ))}
            </select>
          </label>
        )}
        <button type="button" className="secondary-button settings-manage-button" onClick={onOpenModelSettings}>
          Manage models
        </button>
      </div>

      <div className="settings-section">
        <div className="history-group-label">RESEARCH MODEL</div>
        <p className="settings-hint">
          Model the deep research tool uses for its web searches. Leave on the server default, or pick
          one of your enabled models to override it.
        </p>
        {enabledModels.length === 0 ? (
          <p className="history-empty">
            No models are enabled yet.{" "}
            <button type="button" className="link-button" onClick={onOpenModelSettings}>
              Enable a model
            </button>{" "}
            to choose a research model.
          </p>
        ) : (
          <label className="composer-select settings-select">
            <span>Model</span>
            <select
              value={resolvedResearch}
              onChange={(event) => setResearchModelId(event.target.value || null)}
            >
              <option value="">Server default</option>
              {enabledModels.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.name}
                </option>
              ))}
            </select>
          </label>
        )}
      </div>

      <div className="settings-section">
        <div className="history-group-label">MAX TOOL CALLS</div>
        <p className="settings-hint">
          Caps how many tool-call steps the agent may take to answer a single message. Leave blank to
          use the server default.
        </p>
        <p className="settings-current">Current: {maxToolCallsCurrent}</p>
        <label className="composer-select settings-select">
          <span>Limit per message</span>
          <input
            type="number"
            inputMode="numeric"
            min={MAX_TOOL_CALLS_MIN}
            max={MAX_TOOL_CALLS_MAX}
            value={maxToolCallsDraft}
            placeholder="Server default"
            onChange={(event) => setMaxToolCallsDraft(event.target.value)}
            className="settings-number-input"
          />
        </label>
      </div>

      <div className="settings-section">
        <div className="history-group-label">REQUEST TIMEOUT</div>
        <p className="settings-hint">
          How long (in seconds) the agent may work on a single message before timing out. Leave blank to
          use the server default. Allowed range: {REQUEST_TIMEOUT_MIN}–{REQUEST_TIMEOUT_MAX}s.
        </p>
        <p className="settings-current">Current: {requestTimeoutCurrent}</p>
        <label className="composer-select settings-select">
          <span>Seconds per message</span>
          <input
            type="number"
            inputMode="numeric"
            min={REQUEST_TIMEOUT_MIN}
            max={REQUEST_TIMEOUT_MAX}
            value={requestTimeoutDraft}
            placeholder="Server default"
            onChange={(event) => setRequestTimeoutDraft(event.target.value)}
            className="settings-number-input"
          />
        </label>
      </div>

      <div className="settings-section settings-actions">
        <button type="button" className="primary-button" onClick={handleSave} disabled={!dirty}>
          {justSaved ? "Saved" : "Save settings"}
        </button>
      </div>
    </div>
  );
}
