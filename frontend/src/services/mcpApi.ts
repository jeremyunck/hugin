import type { McpDiscoveryResult, McpServer, McpTestResult, McpTool } from "../lib/types";
import { apiFetch } from "./apiClient";

/** Payload for creating an MCP server. The bearer token is write-only and never read back. */
export type McpCreatePayload = {
  name: string;
  displayName: string;
  transport: string;
  endpointUrl: string;
  authType: string;
  bearerToken?: string | null;
};

/** Partial update; omit a field to leave it unchanged. `clearToken` removes a stored token. */
export type McpUpdatePayload = {
  displayName?: string;
  endpointUrl?: string;
  enabled?: boolean;
  authType?: string;
  bearerToken?: string | null;
  clearToken?: boolean;
};

export async function fetchMcpServers(token: string): Promise<McpServer[]> {
  return apiFetch<McpServer[]>("/api/mcp/servers", {}, token);
}

export async function createMcpServer(token: string, payload: McpCreatePayload): Promise<McpServer> {
  return apiFetch<McpServer>(
    "/api/mcp/servers",
    { method: "POST", body: JSON.stringify(payload) },
    token
  );
}

export async function updateMcpServer(
  token: string,
  id: string,
  payload: McpUpdatePayload
): Promise<McpServer> {
  return apiFetch<McpServer>(
    `/api/mcp/servers/${encodeURIComponent(id)}`,
    { method: "PATCH", body: JSON.stringify(payload) },
    token
  );
}

export async function deleteMcpServer(token: string, id: string): Promise<void> {
  await apiFetch<void>(
    `/api/mcp/servers/${encodeURIComponent(id)}`,
    { method: "DELETE" },
    token
  );
}

export async function testMcpServer(token: string, id: string): Promise<McpTestResult> {
  return apiFetch<McpTestResult>(
    `/api/mcp/servers/${encodeURIComponent(id)}/test`,
    { method: "POST", body: JSON.stringify({}) },
    token
  );
}

export async function discoverMcpTools(token: string, id: string): Promise<McpDiscoveryResult> {
  return apiFetch<McpDiscoveryResult>(
    `/api/mcp/servers/${encodeURIComponent(id)}/discover`,
    { method: "POST", body: JSON.stringify({}) },
    token
  );
}

export async function setMcpToolEnabled(
  token: string,
  serverId: string,
  toolId: string,
  enabled: boolean
): Promise<McpTool> {
  return apiFetch<McpTool>(
    `/api/mcp/servers/${encodeURIComponent(serverId)}/tools/${encodeURIComponent(toolId)}`,
    { method: "PATCH", body: JSON.stringify({ enabled }) },
    token
  );
}
