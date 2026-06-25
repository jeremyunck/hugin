import type { AgentRun, FileNode, SandboxInfo, ToolSummary } from "../lib/types";
import { apiFetch } from "./apiClient";

/** Lists the server home directory (~/) file tree backing the "Agent" chat mode. */
export async function fetchAgentWorkspaceFiles(token: string): Promise<FileNode[]> {
  return apiFetch<FileNode[]>("/api/agent/workspace/files", {}, token);
}

export async function createGitHubSandbox(
  token: string,
  repoFullName: string,
  branch: string,
  bugReportId?: string
): Promise<SandboxInfo> {
  return apiFetch<SandboxInfo>(
    "/api/sandboxes/github",
    { method: "POST", body: JSON.stringify({ repoFullName, branch, bugReportId }) },
    token
  );
}

export async function deleteSandbox(token: string, id: string): Promise<void> {
  await fetch(`/api/sandboxes/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` }
  });
}

export async function fetchSandboxFiles(token: string, id: string): Promise<FileNode[]> {
  return apiFetch<FileNode[]>(`/api/sandboxes/${encodeURIComponent(id)}/files`, {}, token);
}

/** Fetches a project chat's sandbox metadata (including its container health status). */
export async function fetchSandbox(token: string, id: string): Promise<SandboxInfo> {
  return apiFetch<SandboxInfo>(`/api/sandboxes/${encodeURIComponent(id)}`, {}, token);
}

export async function fetchTools(token: string, sandboxId?: string): Promise<ToolSummary[]> {
  const query = sandboxId ? `?sandboxId=${encodeURIComponent(sandboxId)}` : "";
  return apiFetch<ToolSummary[]>(`/api/agent/tools${query}`, {}, token);
}

/** Lists agent runs still executing on the server (they continue after a client disconnect). */
export async function fetchAgentRuns(token: string): Promise<AgentRun[]> {
  return apiFetch<AgentRun[]>("/api/agent/runs", {}, token);
}

export async function cancelAgentRun(token: string, id: string): Promise<void> {
  const response = await fetch(`/api/agent/runs/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` }
  });
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const body = await response.json();
      if (body && typeof body.error === "string" && body.error) {
        message = body.error;
      }
    } catch {
      // Keep the status fallback when no JSON body is available.
    }
    throw new Error(message);
  }
}
