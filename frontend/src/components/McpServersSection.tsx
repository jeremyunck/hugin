import { useState } from "react";
import { ChevronDown, ChevronRight, Plus, Server } from "lucide-react";

import type { AuthSession, McpServer, McpTool } from "../lib/types";
import type { Screen } from "../lib/screen";
import { useMcpServers } from "../hooks/useMcpServers";
import type { McpCreatePayload, McpUpdatePayload } from "../services/mcpApi";

/* Phase 1 supports only Streamable HTTP. The transport <select> is rendered but locked to it; new
 * transports (e.g. stdio) can be added here once the backend supports them. */
const TRANSPORT_OPTIONS = [{ value: "STREAMABLE_HTTP", label: "Streamable HTTP" }];

type DialogState =
  | { mode: "closed" }
  | { mode: "add" }
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

  const toggleExpand = (id: string) =>
    setExpanded((current) => ({ ...current, [id]: !current[id] }));

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
            onToggleTool={(toolId, enabled) => mcp.toggleMcpTool(server.id, toolId, enabled)}
          />
        ))}
      </div>

      {dialog.mode !== "closed" ? (
        <McpServerDialog
          server={dialog.mode === "edit" ? dialog.server : null}
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
  onToggleTool: (toolId: string, enabled: boolean) => void;
}) {
  const { server, busy, expanded, testResult } = props;

  return (
    <div className="integration-card mcp-server-card">
      <div className="integration-card-body">
        <div className="integration-card-title-row">
          <span className="integration-card-name">{server.displayName}</span>
          <span className={`mcp-badge ${server.enabled ? "mcp-badge-on" : "mcp-badge-off"}`}>
            {server.enabled ? "Enabled" : "Disabled"}
          </span>
        </div>

        <p className="integration-card-desc mcp-endpoint">{server.endpointUrl}</p>

        <div className="integration-card-meta">
          <span className="integration-meta-item">{transportLabel(server.transport)}</span>
          <span className="integration-meta-item">{authLabel(server.authType, server.hasToken)}</span>
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
  busy: boolean;
  onCancel: () => void;
  onCreate: (payload: McpCreatePayload, discoverAfter: boolean) => void;
  onUpdate: (id: string, payload: McpUpdatePayload) => void;
}) {
  const editing = props.server !== null;
  const [displayName, setDisplayName] = useState(props.server?.displayName ?? "");
  const [name, setName] = useState(props.server?.name ?? "");
  const [endpointUrl, setEndpointUrl] = useState(props.server?.endpointUrl ?? "");
  const [authType, setAuthType] = useState(props.server?.authType ?? "NONE");
  const [bearerToken, setBearerToken] = useState("");
  const [enabled, setEnabled] = useState(props.server?.enabled ?? true);
  const [clearToken, setClearToken] = useState(false);

  const submit = (discoverAfter: boolean) => {
    if (editing && props.server) {
      const payload: McpUpdatePayload = {
        displayName: displayName.trim(),
        endpointUrl: endpointUrl.trim(),
        enabled,
        authType
      };
      if (authType === "BEARER_TOKEN" && bearerToken.trim()) {
        payload.bearerToken = bearerToken.trim();
      }
      if (clearToken) {
        payload.clearToken = true;
      }
      props.onUpdate(props.server.id, payload);
    } else {
      props.onCreate(
        {
          name: name.trim(),
          displayName: displayName.trim(),
          transport: "STREAMABLE_HTTP",
          endpointUrl: endpointUrl.trim(),
          authType,
          bearerToken: authType === "BEARER_TOKEN" ? bearerToken.trim() : null
        },
        discoverAfter
      );
    }
  };

  const canSubmit =
    displayName.trim().length > 0 &&
    endpointUrl.trim().length > 0 &&
    (editing || name.trim().length > 0) &&
    (authType !== "BEARER_TOKEN" || editing || bearerToken.trim().length > 0);

  return (
    <div className="mcp-dialog-overlay" role="dialog" aria-modal="true">
      <div className="mcp-dialog">
        <h3 className="mcp-dialog-title">{editing ? "Edit MCP Server" : "Add MCP Server"}</h3>

        <label className="mcp-field">
          <span className="mcp-field-label">Display Name</span>
          <input
            className="mcp-input"
            value={displayName}
            placeholder="Linear"
            onChange={(e) => setDisplayName(e.target.value)}
          />
        </label>

        {!editing ? (
          <label className="mcp-field">
            <span className="mcp-field-label">Server Name</span>
            <input
              className="mcp-input"
              value={name}
              placeholder="linear"
              onChange={(e) => setName(e.target.value)}
            />
            <span className="mcp-field-hint">Used to build tool names (mcp_&lt;name&gt;_&lt;tool&gt;).</span>
          </label>
        ) : null}

        <label className="mcp-field">
          <span className="mcp-field-label">Transport</span>
          <select className="mcp-input" value="STREAMABLE_HTTP" disabled>
            {TRANSPORT_OPTIONS.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </select>
        </label>

        <label className="mcp-field">
          <span className="mcp-field-label">Endpoint URL</span>
          <input
            className="mcp-input"
            value={endpointUrl}
            placeholder="https://example.com/mcp"
            onChange={(e) => setEndpointUrl(e.target.value)}
          />
        </label>

        <label className="mcp-field">
          <span className="mcp-field-label">Authentication</span>
          <select className="mcp-input" value={authType} onChange={(e) => setAuthType(e.target.value)}>
            <option value="NONE">None</option>
            <option value="BEARER_TOKEN">Bearer Token</option>
          </select>
        </label>

        {authType === "BEARER_TOKEN" ? (
          <label className="mcp-field">
            <span className="mcp-field-label">Bearer Token</span>
            <input
              className="mcp-input"
              type="password"
              value={bearerToken}
              placeholder={editing && props.server?.hasToken ? "•••••• (leave blank to keep)" : "Paste token"}
              onChange={(e) => setBearerToken(e.target.value)}
            />
            {editing && props.server?.hasToken ? (
              <label className="mcp-checkbox-row">
                <input
                  type="checkbox"
                  checked={clearToken}
                  onChange={(e) => setClearToken(e.target.checked)}
                />
                <span>Clear stored token</span>
              </label>
            ) : null}
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
          <button
            type="button"
            className="mcp-btn-primary"
            disabled={!canSubmit || props.busy}
            onClick={() => submit(false)}
          >
            Save
          </button>
          {!editing ? (
            <button
              type="button"
              className="mcp-btn-primary"
              disabled={!canSubmit || props.busy}
              onClick={() => submit(true)}
            >
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

function transportLabel(transport: string): string {
  return transport === "STREAMABLE_HTTP" ? "Streamable HTTP" : transport;
}

function authLabel(authType: string, hasToken: boolean): string {
  if (authType === "BEARER_TOKEN") {
    return hasToken ? "Bearer token" : "Bearer token (missing)";
  }
  return "No auth";
}
