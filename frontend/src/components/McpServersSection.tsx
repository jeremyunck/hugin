import { useState } from "react";
import { ChevronDown, ChevronRight, Plus, Server } from "lucide-react";

import type { AuthSession, McpCatalogEntry, McpServer, McpTool } from "../lib/types";
import type { Screen } from "../lib/screen";
import { useMcpServers } from "../hooks/useMcpServers";
import type { McpCreatePayload, McpUpdatePayload } from "../services/mcpApi";

const TRANSPORT_OPTIONS = [
  { value: "STREAMABLE_HTTP", label: "Streamable HTTP" },
  { value: "STDIO", label: "Local process (stdio)" }
];

/** Prefill for the Add dialog, e.g. when adding from the catalog. */
type AddPrefill = {
  name?: string;
  displayName?: string;
  transport?: string;
  endpointUrl?: string;
  authType?: string;
};

type DialogState =
  | { mode: "closed" }
  | { mode: "add"; prefill?: AddPrefill }
  | { mode: "edit"; server: McpServer };

export function McpServersSection(props: {
  session: AuthSession | null;
  screen: Screen;
  onError: (message: string) => void;
  onChanged?: () => void;
}) {
  const mcp = useMcpServers(props);
  const [dialog, setDialog] = useState<DialogState>({ mode: "closed" });
  const [removeTarget, setRemoveTarget] = useState<McpServer | null>(null);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [showCatalog, setShowCatalog] = useState(false);

  const toggleExpand = (id: string) =>
    setExpanded((current) => ({ ...current, [id]: !current[id] }));

  const installedNames = new Set(mcp.mcpServers.map((s) => s.name));

  return (
    <div className="integrations-section mcp-section">
      <div className="integrations-section-head">
        <div>
          <div className="integrations-section-label">MCP Servers</div>
          <div className="integrations-section-sub">Connect custom MCP servers.</div>
        </div>
        <button
          type="button"
          className="mcp-add-server-button"
          onClick={() => setDialog({ mode: "add" })}
        >
          <Plus size={15} strokeWidth={2.4} />
          Add Server
        </button>
      </div>

      {mcp.mcpCatalog.length > 0 ? (
        <div className="mcp-catalog">
          <button type="button" className="integration-link-action mcp-expand-button"
                  onClick={() => setShowCatalog((v) => !v)}>
            {showCatalog ? <ChevronDown size={14} strokeWidth={2.2} /> : <ChevronRight size={14} strokeWidth={2.2} />}
            Browse catalog
          </button>
          {showCatalog ? (
            <div className="mcp-catalog-grid">
              {mcp.mcpCatalog.map((entry) => (
                <McpCatalogCard
                  key={entry.id}
                  entry={entry}
                  installed={installedNames.has(entry.suggestedServerName)}
                  onAdd={() => setDialog({ mode: "add", prefill: {
                    name: entry.suggestedServerName,
                    displayName: entry.name,
                    transport: entry.transport,
                    endpointUrl: entry.endpointUrl,
                    authType: entry.authType
                  } })}
                />
              ))}
            </div>
          ) : null}
        </div>
      ) : null}

      {mcp.mcpLoading && mcp.mcpServers.length === 0 ? (
        <p className="integration-subtitle">Loading MCP servers…</p>
      ) : null}

      {!mcp.mcpLoading && mcp.mcpServers.length === 0 ? (
        <div className="mcp-empty">
          <Server size={22} strokeWidth={1.5} color="#8b9099" />
          <p className="integrations-empty-text">
            No MCP servers yet. Add one to expose its tools to Hugin.
          </p>
        </div>
      ) : null}

      <div className="integrations-cards">
        {mcp.mcpServers.map((server) => (
          <McpServerCard
            key={server.id}
            server={server}
            busy={mcp.mcpBusyId === server.id}
            expanded={Boolean(expanded[server.id])}
            testResult={mcp.mcpTestResults[server.id]}
            onToggleExpand={() => toggleExpand(server.id)}
            onTest={() => mcp.testMcpServer(server.id)}
            onDiscover={() => mcp.discoverMcpTools(server.id)}
            onEdit={() => setDialog({ mode: "edit", server })}
            onRemove={() => setRemoveTarget(server)}
            onConnectOAuth={() => mcp.connectMcpOAuth(server.id)}
            onToggleTool={(toolId, enabled) => mcp.toggleMcpTool(server.id, toolId, enabled)}
          />
        ))}
      </div>

      {dialog.mode !== "closed" ? (
        <McpServerDialog
          server={dialog.mode === "edit" ? dialog.server : null}
          prefill={dialog.mode === "add" ? dialog.prefill : undefined}
          busy={mcp.mcpBusyId === "new" || (dialog.mode === "edit" && mcp.mcpBusyId === dialog.server.id)}
          onCancel={() => setDialog({ mode: "closed" })}
          onCreate={async (payload, discoverAfter) => {
            const created = await mcp.createMcpServer(payload, discoverAfter);
            if (created) setDialog({ mode: "closed" });
          }}
          onUpdate={async (id, payload) => {
            const ok = await mcp.updateMcpServer(id, payload);
            if (ok) setDialog({ mode: "closed" });
          }}
        />
      ) : null}

      {removeTarget ? (
        <McpRemoveDialog
          server={removeTarget}
          busy={mcp.mcpBusyId === removeTarget.id}
          onCancel={() => setRemoveTarget(null)}
          onConfirm={async () => {
            await mcp.removeMcpServer(removeTarget.id);
            setRemoveTarget(null);
          }}
        />
      ) : null}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Catalog card                                                       */
/* ------------------------------------------------------------------ */

function McpCatalogCard(props: { entry: McpCatalogEntry; installed: boolean; onAdd: () => void }) {
  const { entry, installed } = props;
  return (
    <div className="mcp-catalog-card">
      <div className="mcp-catalog-card-body">
        <span className="integration-card-name">{entry.name}</span>
        <p className="integration-card-desc">{entry.description}</p>
      </div>
      <button type="button" className="mcp-btn-secondary" disabled={installed} onClick={props.onAdd}>
        {installed ? "Added" : "Add"}
      </button>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Server card                                                        */
/* ------------------------------------------------------------------ */

function McpServerCard(props: {
  server: McpServer;
  busy: boolean;
  expanded: boolean;
  testResult?: { success: boolean; message: string };
  onToggleExpand: () => void;
  onTest: () => void;
  onDiscover: () => void;
  onEdit: () => void;
  onRemove: () => void;
  onConnectOAuth: () => void;
  onToggleTool: (toolId: string, enabled: boolean) => void;
}) {
  const { server, busy, expanded, testResult } = props;
  const isOAuth = server.authType === "OAUTH";

  return (
    <div className="integration-card mcp-server-card">
      <div className="integration-card-body">
        <div className="integration-card-title-row">
          <span className="integration-card-name">{server.displayName}</span>
          <span className={`mcp-badge ${server.enabled ? "mcp-badge-on" : "mcp-badge-off"}`}>
            {server.enabled ? "Enabled" : "Disabled"}
          </span>
          {isOAuth ? (
            <span className={`mcp-badge ${server.oauthConnected ? "mcp-badge-on" : "mcp-badge-off"}`}>
              {server.oauthConnected ? "Authorized" : "Not authorized"}
            </span>
          ) : null}
        </div>

        <p className="integration-card-desc mcp-endpoint">
          {server.transport === "STDIO" ? (server.command ?? "(no command)") : server.endpointUrl}
        </p>

        <div className="integration-card-meta">
          <span className="integration-meta-item">{transportLabel(server.transport)}</span>
          <span className="integration-meta-item">{authLabel(server)}</span>
          <span className="integration-meta-item">
            {server.enabledToolCount}/{server.toolCount} tool{server.toolCount === 1 ? "" : "s"} enabled
          </span>
        </div>

        {testResult ? (
          <p className={`mcp-test-result ${testResult.success ? "mcp-test-ok" : "mcp-test-err"}`}>
            {testResult.message}
          </p>
        ) : null}

        <div className="integration-card-actions mcp-card-actions">
          {isOAuth ? (
            <button type="button" className="integration-link-action" disabled={busy} onClick={props.onConnectOAuth}>
              {server.oauthConnected ? "Reconnect" : "Connect"}
            </button>
          ) : null}
          <button type="button" className="integration-link-action" disabled={busy} onClick={props.onTest}>
            {busy ? "Working…" : "Test"}
          </button>
          <button type="button" className="integration-link-action" disabled={busy} onClick={props.onDiscover}>
            Discover Tools
          </button>
          <button type="button" className="integration-link-action" disabled={busy} onClick={props.onEdit}>
            Edit
          </button>
          <button
            type="button"
            className="integration-link-action integration-link-danger"
            disabled={busy}
            onClick={props.onRemove}
          >
            Remove
          </button>
          <button type="button" className="integration-link-action mcp-expand-button" onClick={props.onToggleExpand}>
            {expanded ? <ChevronDown size={14} strokeWidth={2.2} /> : <ChevronRight size={14} strokeWidth={2.2} />}
            {expanded ? "Hide tools" : "Show tools"}
          </button>
        </div>

        {expanded ? (
          <div className="mcp-tool-list">
            {server.tools.length === 0 ? (
              <p className="integration-card-desc">No tools discovered yet. Click “Discover Tools”.</p>
            ) : (
              server.tools.map((tool) => (
                <McpToolRow
                  key={tool.id}
                  tool={tool}
                  onToggle={(enabled) => props.onToggleTool(tool.id, enabled)}
                />
              ))
            )}
          </div>
        ) : null}
      </div>
    </div>
  );
}

function McpToolRow(props: { tool: McpTool; onToggle: (enabled: boolean) => void }) {
  const { tool } = props;
  return (
    <div className={`mcp-tool-row ${tool.stale ? "mcp-tool-stale" : ""}`}>
      <label className="mcp-toggle">
        <input
          type="checkbox"
          checked={tool.enabled}
          disabled={tool.stale}
          onChange={(e) => props.onToggle(e.target.checked)}
        />
        <span className="mcp-toggle-track" />
      </label>
      <div className="mcp-tool-info">
        <div className="mcp-tool-names">
          <code className="mcp-tool-hugin">{tool.huginToolName}</code>
          <span className="mcp-tool-original">({tool.toolName})</span>
          {tool.stale ? <span className="mcp-tool-stale-tag">stale</span> : null}
        </div>
        {tool.description ? <p className="mcp-tool-desc">{tool.description}</p> : null}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Add / Edit dialog                                                  */
/* ------------------------------------------------------------------ */

function McpServerDialog(props: {
  server: McpServer | null;
  prefill?: AddPrefill;
  busy: boolean;
  onCancel: () => void;
  onCreate: (payload: McpCreatePayload, discoverAfter: boolean) => void;
  onUpdate: (id: string, payload: McpUpdatePayload) => void;
}) {
  const editing = props.server !== null;
  const initial = props.server ?? props.prefill;
  const [displayName, setDisplayName] = useState(initial?.displayName ?? "");
  const [name, setName] = useState((props.server?.name ?? props.prefill?.name) ?? "");
  const [transport, setTransport] = useState(initial?.transport ?? "STREAMABLE_HTTP");
  const [endpointUrl, setEndpointUrl] = useState(props.server?.endpointUrl ?? props.prefill?.endpointUrl ?? "");
  const [authType, setAuthType] = useState(initial?.authType ?? "NONE");
  const [bearerToken, setBearerToken] = useState("");
  const [enabled, setEnabled] = useState(props.server?.enabled ?? true);
  const [clearToken, setClearToken] = useState(false);
  const [command, setCommand] = useState(props.server?.command ?? "");
  const [argsText, setArgsText] = useState("");
  const [envText, setEnvText] = useState("");
  const [oauthScope, setOauthScope] = useState("");

  const isStdio = transport === "STDIO";

  const submit = (discoverAfter: boolean) => {
    if (editing && props.server) {
      const payload: McpUpdatePayload = { displayName: displayName.trim(), enabled, authType };
      if (!isStdio) {
        payload.endpointUrl = endpointUrl.trim();
      }
      if (isStdio && command.trim()) {
        payload.command = command.trim();
        if (argsText.trim()) payload.args = parseArgs(argsText);
        if (envText.trim()) payload.env = parseEnv(envText);
      }
      if (authType === "BEARER_TOKEN" && bearerToken.trim()) {
        payload.bearerToken = bearerToken.trim();
      }
      if (authType === "OAUTH" && oauthScope.trim()) {
        payload.oauthScope = oauthScope.trim();
      }
      if (clearToken) {
        payload.clearToken = true;
      }
      props.onUpdate(props.server.id, payload);
    } else {
      const payload: McpCreatePayload = {
        name: name.trim(),
        displayName: displayName.trim(),
        transport,
        authType: isStdio ? "NONE" : authType
      };
      if (isStdio) {
        payload.command = command.trim();
        if (argsText.trim()) payload.args = parseArgs(argsText);
        if (envText.trim()) payload.env = parseEnv(envText);
      } else {
        payload.endpointUrl = endpointUrl.trim();
        if (authType === "BEARER_TOKEN") payload.bearerToken = bearerToken.trim();
        if (authType === "OAUTH" && oauthScope.trim()) payload.oauthScope = oauthScope.trim();
      }
      props.onCreate(payload, discoverAfter);
    }
  };

  const canSubmit =
    displayName.trim().length > 0 &&
    (editing || name.trim().length > 0) &&
    (isStdio ? (editing || command.trim().length > 0) : endpointUrl.trim().length > 0) &&
    (authType !== "BEARER_TOKEN" || isStdio || editing || bearerToken.trim().length > 0);

  return (
    <div className="mcp-dialog-overlay" role="dialog" aria-modal="true">
      <div className="mcp-dialog">
        <h3 className="mcp-dialog-title">{editing ? "Edit MCP Server" : "Add MCP Server"}</h3>

        <label className="mcp-field">
          <span className="mcp-field-label">Display Name</span>
          <input className="mcp-input" value={displayName} placeholder="Linear"
                 onChange={(e) => setDisplayName(e.target.value)} />
        </label>

        {!editing ? (
          <label className="mcp-field">
            <span className="mcp-field-label">Server Name</span>
            <input className="mcp-input" value={name} placeholder="linear"
                   onChange={(e) => setName(e.target.value)} />
            <span className="mcp-field-hint">Used to build tool names (mcp_&lt;name&gt;_&lt;tool&gt;).</span>
          </label>
        ) : null}

        <label className="mcp-field">
          <span className="mcp-field-label">Transport</span>
          <select className="mcp-input" value={transport} disabled={editing}
                  onChange={(e) => setTransport(e.target.value)}>
            {TRANSPORT_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>{opt.label}</option>
            ))}
          </select>
          {isStdio ? (
            <span className="mcp-field-hint">Runs a local process. Must be enabled on the server.</span>
          ) : null}
        </label>

        {isStdio ? (
          <>
            <label className="mcp-field">
              <span className="mcp-field-label">Command</span>
              <input className="mcp-input" value={command} placeholder="npx"
                     onChange={(e) => setCommand(e.target.value)} />
            </label>
            <label className="mcp-field">
              <span className="mcp-field-label">Arguments</span>
              <input className="mcp-input" value={argsText} placeholder="-y @modelcontextprotocol/server-foo"
                     onChange={(e) => setArgsText(e.target.value)} />
              <span className="mcp-field-hint">Space-separated.</span>
            </label>
            <label className="mcp-field">
              <span className="mcp-field-label">Environment</span>
              <textarea className="mcp-input" rows={2} value={envText} placeholder={"KEY=value\nOTHER=value"}
                        onChange={(e) => setEnvText(e.target.value)} />
              <span className="mcp-field-hint">One KEY=value per line.</span>
            </label>
          </>
        ) : (
          <label className="mcp-field">
            <span className="mcp-field-label">Endpoint URL</span>
            <input className="mcp-input" value={endpointUrl} placeholder="https://example.com/mcp"
                   onChange={(e) => setEndpointUrl(e.target.value)} />
          </label>
        )}

        {!isStdio ? (
          <label className="mcp-field">
            <span className="mcp-field-label">Authentication</span>
            <select className="mcp-input" value={authType} onChange={(e) => setAuthType(e.target.value)}>
              <option value="NONE">None</option>
              <option value="BEARER_TOKEN">Bearer Token</option>
              <option value="OAUTH">OAuth</option>
            </select>
          </label>
        ) : null}

        {!isStdio && authType === "BEARER_TOKEN" ? (
          <label className="mcp-field">
            <span className="mcp-field-label">Bearer Token</span>
            <input className="mcp-input" type="password" value={bearerToken}
                   placeholder={editing && props.server?.hasToken ? "•••••• (leave blank to keep)" : "Paste token"}
                   onChange={(e) => setBearerToken(e.target.value)} />
            {editing && props.server?.hasToken ? (
              <label className="mcp-checkbox-row">
                <input type="checkbox" checked={clearToken} onChange={(e) => setClearToken(e.target.checked)} />
                <span>Clear stored token</span>
              </label>
            ) : null}
          </label>
        ) : null}

        {!isStdio && authType === "OAUTH" ? (
          <label className="mcp-field">
            <span className="mcp-field-label">OAuth Scope (optional)</span>
            <input className="mcp-input" value={oauthScope} placeholder="read write"
                   onChange={(e) => setOauthScope(e.target.value)} />
            <span className="mcp-field-hint">
              After saving, click “Connect” on the server card to authorize.
            </span>
          </label>
        ) : null}

        {editing ? (
          <label className="mcp-checkbox-row">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            <span>Server enabled</span>
          </label>
        ) : null}

        <div className="mcp-dialog-actions">
          <button type="button" className="mcp-btn-secondary" disabled={props.busy} onClick={props.onCancel}>
            Cancel
          </button>
          <button type="button" className="mcp-btn-primary" disabled={!canSubmit || props.busy}
                  onClick={() => submit(false)}>
            Save
          </button>
          {!editing ? (
            <button type="button" className="mcp-btn-primary" disabled={!canSubmit || props.busy}
                    onClick={() => submit(true)}>
              Save &amp; Discover
            </button>
          ) : null}
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Remove confirmation                                                */
/* ------------------------------------------------------------------ */

function McpRemoveDialog(props: {
  server: McpServer;
  busy: boolean;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  return (
    <div className="mcp-dialog-overlay" role="dialog" aria-modal="true">
      <div className="mcp-dialog">
        <h3 className="mcp-dialog-title">Remove “{props.server.displayName}”?</h3>
        <p className="integration-card-desc">
          This deletes the server and all {props.server.toolCount} discovered tool
          {props.server.toolCount === 1 ? "" : "s"}. This cannot be undone.
        </p>
        <div className="mcp-dialog-actions">
          <button type="button" className="mcp-btn-secondary" disabled={props.busy} onClick={props.onCancel}>
            Cancel
          </button>
          <button type="button" className="mcp-btn-danger" disabled={props.busy} onClick={props.onConfirm}>
            {props.busy ? "Removing…" : "Remove"}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ */
/*  Helpers                                                            */
/* ------------------------------------------------------------------ */

function parseArgs(text: string): string[] {
  return text.trim().split(/\s+/).filter((a) => a.length > 0);
}

function parseEnv(text: string): Record<string, string> {
  const env: Record<string, string> = {};
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const eq = trimmed.indexOf("=");
    if (eq > 0) {
      env[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
    }
  }
  return env;
}

function transportLabel(transport: string): string {
  if (transport === "STREAMABLE_HTTP") return "Streamable HTTP";
  if (transport === "STDIO") return "Local process (stdio)";
  return transport;
}

function authLabel(server: McpServer): string {
  switch (server.authType) {
    case "BEARER_TOKEN":
      return server.hasToken ? "Bearer token" : "Bearer token (missing)";
    case "OAUTH":
      return server.oauthConnected ? "OAuth (authorized)" : "OAuth (needs authorization)";
    default:
      return "No auth";
  }
}
