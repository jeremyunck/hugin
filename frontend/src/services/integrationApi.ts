import type {
  BugReportResponse,
  BugReportSummary,
  Integration,
  ModelOption
} from "../lib/types";
import { apiFetch } from "./apiClient";

export async function fetchIntegrations(token: string): Promise<Integration[]> {
  return apiFetch<Integration[]>("/api/integrations", {}, token);
}

type ModelSettingsResponse = {
  models: ModelOption[];
};

export async function fetchModels(token: string, enabledOnly = false): Promise<ModelOption[]> {
  const query = enabledOnly ? "?enabledOnly=true" : "";
  const response = await apiFetch<ModelSettingsResponse>(`/api/models${query}`, {}, token);
  return response.models;
}

export async function saveEnabledModels(token: string, enabledModelIds: string[]): Promise<ModelOption[]> {
  const response = await apiFetch<ModelSettingsResponse>(
    "/api/models/preferences",
    { method: "PUT", body: JSON.stringify({ enabledModelIds }) },
    token
  );
  return response.models;
}

type GoogleReconnectResponse = {
  status: Integration | Record<string, unknown>;
  authUrl: string | null;
};

export async function reconnectGoogle(token: string, returnTo: string): Promise<string | null> {
  const response = await apiFetch<GoogleReconnectResponse>(
    "/api/google/reconnect",
    { method: "POST", body: JSON.stringify({ returnTo }) },
    token
  );
  return response.authUrl;
}

export async function disconnectGoogle(token: string): Promise<void> {
  await apiFetch("/api/google/disconnect", { method: "POST", body: JSON.stringify({}) }, token);
}

type ReportBugRequest = {
  sessionId: string;
  title: string;
  sandboxId?: string;
  agentId?: string;
  thread: unknown;
  clientContext: unknown;
};

export async function reportBug(token: string, payload: ReportBugRequest): Promise<BugReportResponse> {
  return apiFetch<BugReportResponse>(
    "/api/agent/bug-report",
    { method: "POST", body: JSON.stringify(payload) },
    token
  );
}

export async function fetchBugReports(token: string): Promise<BugReportSummary[]> {
  return apiFetch<BugReportSummary[]>("/api/agent/bug-reports", {}, token);
}
