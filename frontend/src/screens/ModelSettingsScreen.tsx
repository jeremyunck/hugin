import { useState } from "react";
import { ArrowLeft, Search } from "lucide-react";

import type { ModelOption } from "../lib/types";
import { COLORS } from "../lib/theme";

function formatPrice(value?: string | null) {
  if (!value) return "N/A";
  const amount = Number(value);
  if (Number.isNaN(amount)) return value;
  if (amount === 0) return "$0.00";
  if (amount >= 1) return `$${amount.toFixed(2)}`;
  if (amount >= 0.01) return `$${amount.toFixed(3)}`;
  return `$${amount.toFixed(4)}`;
}

function formatContext(value?: number | null) {
  if (!value) return null;
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(value % 1_000_000 === 0 ? 0 : 1)}M ctx`;
  if (value >= 1_000) return `${Math.round(value / 1_000)}K ctx`;
  return `${value} ctx`;
}

/** Lets the user choose which OpenRouter models appear in the chat composer. */
export function ModelSettingsScreen(props: {
  models: ModelOption[];
  saving: boolean;
  onBack: () => void;
  onToggle: (modelId: string) => void;
  onSave: () => void;
}) {
  const { models, saving, onBack, onToggle, onSave } = props;
  const [searchQuery, setSearchQuery] = useState("");
  const enabledCount = models.filter((model) => model.enabled).length;

  const filteredModels = searchQuery.trim()
    ? models.filter(
        (model) =>
          model.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
          model.id.toLowerCase().includes(searchQuery.toLowerCase())
      )
    : models;

  return (
    <>
      <div className="back-row">
        <button type="button" className="icon-button back-button" onClick={onBack} aria-label="Back">
          <ArrowLeft size={22} strokeWidth={2} />
        </button>
      </div>

      <div className="screen-pad">
        <h1 className="screen-title integration-title">Model settings</h1>
        <p className="integration-subtitle">
          Choose which OpenRouter models appear in chat. Prices are shown per million input and output tokens.
        </p>
      </div>

      <div className="screen-pad">
        <div className="search-bar">
          <Search size={17} strokeWidth={2} color={COLORS.faint} />
          <input
            value={searchQuery}
            onChange={(event) => setSearchQuery(event.target.value)}
            placeholder="Search models…"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
          />
        </div>
      </div>

      <div className="integrations-list">
        <div className="history-group-label">AVAILABLE MODELS</div>
        {filteredModels.length === 0 ? (
          <p className="history-empty">No models match your search.</p>
        ) : (
          filteredModels.map((model) => (
            <label key={model.id} className={`model-card ${model.enabled ? "model-card-enabled" : ""}`}>
              <div className="model-card-main">
                <div className="model-toggle">
                  <input type="checkbox" checked={model.enabled} onChange={() => onToggle(model.id)} />
                </div>
                <div className="integration-copy">
                  <div className="integration-name-row">
                    <span className="integration-name">{model.name}</span>
                    {model.enabled ? <span className="integration-badge">ENABLED</span> : null}
                  </div>
                  <div className="integration-meta">{model.id}</div>
                  {model.description ? <div className="model-description">{model.description}</div> : null}
                  <div className="model-metrics">
                    <span>Input {formatPrice(model.promptPrice)}/M</span>
                    <span>Output {formatPrice(model.completionPrice)}/M</span>
                    {formatContext(model.contextLength) ? <span>{formatContext(model.contextLength)}</span> : null}
                  </div>
                </div>
              </div>
            </label>
          ))
        )}
      </div>

      <div className="screen-pad history-footer">
        <button type="button" className="primary-button" onClick={onSave} disabled={saving || enabledCount === 0}>
          {saving ? "Saving…" : "Save model settings"}
        </button>
      </div>
    </>
  );
}
