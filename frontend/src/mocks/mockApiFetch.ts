import type { SandboxInfo } from "../lib/types";
import {
  MOCK_NOW,
  mockAuthMe,
  mockOpenRouterCredits,
  mockOpenRouterKeyStatus,
  mockSession,
  mockUserProfile
} from "./fixtures/auth";
import { mockModels } from "./fixtures/models";
import { mockIntegrations } from "./fixtures/integrations";
import {
  mockBranchesFor,
  mockBugReports,
  mockGitHubStatus,
  mockRepositories,
  mockRepositoryDetail
} from "./fixtures/github";
import { mockAgentRuns, mockTools } from "./fixtures/agentRuns";
import { mockAgentFiles, mockProjectFiles } from "./fixtures/workspace";
import { mockEventsFor } from "./fixtures/threads";

/**
 * The single place all mocked backend responses are assembled. Every service module funnels through
 * `apiFetch`, which routes here in mock mode, so components and hooks never need a `mockMode`
 * conditional — they keep calling the same service functions and transparently receive fixture data.
 *
 * The router matches on HTTP method + path. Reads return fixtures; writes echo back a plausible
 * success shape so flows like "save settings" or "send a message" complete without a backend.
 */

function ok<T>(value: T): Promise<T> {
  // Deep-clone so callers that mutate results can't corrupt the shared fixtures between screens.
  return Promise.resolve(structuredClone(value) as T);
}

function pathOf(rawPath: string): string {
  const [path] = rawPath.split("?");
  return path;
}

function sandbox(id: string): SandboxInfo {
  return {
    id,
    containerName: `bouw-${id}`,
    image: "ghcr.io/bouw/sandbox:latest",
    status: "running",
    createdAt: MOCK_NOW,
    workspace: "/workspace"
  };
}

function sessionIdFromEventsPath(path: string): string {
  // /api/chat/sessions/{id}/events  ->  {id}
  const match = path.match(/\/api\/chat\/sessions\/([^/]+)\/events/);
  return match ? decodeURIComponent(match[1]) : "";
}

export function mockApiFetch<T>(rawPath: string, init: RequestInit = {}): Promise<T> {
  const method = (init.method ?? "GET").toUpperCase();
  const path = pathOf(rawPath);
  const route = `${method} ${path}`;

  // --- Auth & profile -------------------------------------------------------
  if (route === "GET /api/auth/me") return ok(mockAuthMe) as Promise<T>;
  if (route === "POST /api/auth/login" || route === "POST /api/auth/register") {
    return ok({ email: mockSession.username, verificationRequired: true, message: "We emailed you a 6-digit code." }) as Promise<T>;
  }
  if (route === "POST /api/auth/verify" || route === "POST /api/auth/password/forgot/verify") {
    return ok({
      token: mockSession.token,
      tokenType: "Bearer",
      expiresAt: mockSession.expiresAt,
      username: mockSession.username,
      roles: mockSession.roles
    }) as Promise<T>;
  }
  if (route === "POST /api/auth/password/forgot") {
    return ok({ email: mockSession.username, verificationRequired: true, message: "If that account exists, we emailed a 6-digit code." }) as Promise<T>;
  }
  if (route === "GET /api/user/profile") return ok(mockUserProfile) as Promise<T>;
  if (route === "PUT /api/user/profile") {
    const body = parseBody(init.body);
    return ok({ ...mockUserProfile, ...body }) as Promise<T>;
  }
  if (path === "/api/user/openrouter-key") {
    if (method === "DELETE") return ok({ configured: false, last4: null }) as Promise<T>;
    return ok(mockOpenRouterKeyStatus) as Promise<T>;
  }
  if (route === "GET /api/user/openrouter-credits") return ok(mockOpenRouterCredits) as Promise<T>;
  if (path.startsWith("/api/user/password/reset")) {
    return ok({ email: mockSession.username, verificationRequired: true, message: "We emailed you a 6-digit code." }) as Promise<T>;
  }

  // --- Chat sessions --------------------------------------------------------
  if (route === "GET /api/chat/sessions") return ok([]) as Promise<T>;

  // --- Models ---------------------------------------------------------------
  if (path === "/api/models" || path === "/api/models/preferences") {
    return ok({ models: mockModels }) as Promise<T>;
  }

  // --- Integrations ---------------------------------------------------------
  if (route === "GET /api/integrations") return ok(mockIntegrations) as Promise<T>;

  // --- MCP servers (no servers connected in the mock environment) -----------
  if (route === "GET /api/mcp/servers") return ok([]) as Promise<T>;
  if (route === "GET /api/mcp/catalog") return ok([]) as Promise<T>;
  if (method === "POST" && /\/api\/mcp\/servers\/[^/]+\/oauth\/start$/.test(path)) {
    return ok({ authorizationUrl: "https://example.com/authorize?mock=1" }) as Promise<T>;
  }
  if (route === "POST /api/mcp/servers") {
    const body = parseBody(init.body) as Record<string, unknown>;
    return ok({
      id: "mock-mcp-1",
      name: body.name ?? "mock",
      displayName: body.displayName ?? "Mock",
      transport: "STREAMABLE_HTTP",
      endpointUrl: body.endpointUrl ?? "https://example.com/mcp",
      authType: body.authType ?? "NONE",
      enabled: true,
      hasToken: body.authType === "BEARER_TOKEN",
      oauthConnected: false,
      needsAuthorization: body.authType === "OAUTH",
      command: body.command ?? null,
      toolCount: 0,
      enabledToolCount: 0,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      tools: []
    }) as Promise<T>;
  }
  if (method === "POST" && /\/api\/mcp\/servers\/[^/]+\/test$/.test(path)) {
    return ok({ success: false, message: "Mock mode does not reach external MCP servers.", serverName: null, serverVersion: null, protocolVersion: null }) as Promise<T>;
  }
  if (method === "POST" && /\/api\/mcp\/servers\/[^/]+\/discover$/.test(path)) {
    return ok({ success: true, message: "Discovered 0 tool(s).", discoveredCount: 0, tools: [] }) as Promise<T>;
  }
  if (method === "PATCH" && /\/api\/mcp\/servers\/[^/]+\/tools\/[^/]+$/.test(path)) {
    const body = parseBody(init.body) as Record<string, unknown>;
    return ok({ id: "mock-tool", toolName: "mock", bouwToolName: "mcp_mock", description: null, enabled: Boolean(body.enabled), stale: false, lastSeenAt: null }) as Promise<T>;
  }
  if (method === "PATCH" && /\/api\/mcp\/servers\/[^/]+$/.test(path)) {
    return ok({ id: "mock-mcp-1", name: "mock", displayName: "Mock", transport: "STREAMABLE_HTTP", endpointUrl: "https://example.com/mcp", authType: "NONE", enabled: true, hasToken: false, oauthConnected: false, needsAuthorization: false, command: null, toolCount: 0, enabledToolCount: 0, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), tools: [] }) as Promise<T>;
  }
  if (method === "DELETE" && /\/api\/mcp\/servers\/[^/]+$/.test(path)) {
    return ok({}) as Promise<T>;
  }

  if (route === "POST /api/google/reconnect") return ok({ status: {}, authUrl: null }) as Promise<T>;
  if (route === "POST /api/google/disconnect") return ok({}) as Promise<T>;

  // --- GitHub ---------------------------------------------------------------
  if (route === "GET /api/github/status") return ok(mockGitHubStatus) as Promise<T>;
  if (route === "POST /api/github/connect") return ok({ status: { message: mockGitHubStatus.message }, installUrl: null }) as Promise<T>;
  if (route === "POST /api/github/disconnect") return ok({}) as Promise<T>;
  if (route === "GET /api/github/repositories") return ok(mockRepositories) as Promise<T>;
  if (method === "GET" && /\/api\/github\/repositories\/[^/]+\/[^/]+\/branches$/.test(path)) {
    return ok(mockBranchesFor(repoFullNameFromPath(path))) as Promise<T>;
  }
  if (method === "GET" && /\/api\/github\/repositories\/[^/]+\/[^/]+$/.test(path)) {
    return ok(mockRepositoryDetail(repoFullNameFromPath(path))) as Promise<T>;
  }

  // --- Chat sessions --------------------------------------------------------
  if (method === "GET" && /\/api\/chat\/sessions\/[^/]+\/events$/.test(path)) {
    const sessionId = sessionIdFromEventsPath(path);
    const afterSeq = Number(new URLSearchParams(rawPath.split("?")[1] ?? "").get("afterSeq") ?? 0);
    return ok({ sessionId, events: mockEventsFor(sessionId, afterSeq) }) as Promise<T>;
  }
  if (method === "POST" && /\/api\/chat\/sessions\/[^/]+\/messages$/.test(path)) {
    const sessionId = decodeURIComponent(path.split("/")[4]);
    return ok({ sessionId, messageId: `${sessionId}-pending`, runId: `${sessionId}-run`, lastSeq: 0 }) as Promise<T>;
  }

  // --- Agent runs, history, tools, bug reports ------------------------------
  if (route === "GET /api/agent/runs") return ok(mockAgentRuns) as Promise<T>;
  if (route === "GET /api/agent/history") return ok([]) as Promise<T>;
  if (route === "GET /api/agent/workspace/files") return ok(mockAgentFiles) as Promise<T>;
  if (path === "/api/agent/tools") return ok(mockTools) as Promise<T>;
  if (route === "GET /api/agent/bug-reports") return ok(mockBugReports) as Promise<T>;
  if (route === "POST /api/agent/bug-report") {
    return ok({ id: "bug-mock", relativePath: "bug-reports/bug-mock.json", logFiles: [] }) as Promise<T>;
  }

  // --- Sandboxes ------------------------------------------------------------
  if (method === "POST" && path === "/api/sandboxes/github") {
    return ok(sandbox("sandbox-bouw-demo")) as Promise<T>;
  }
  if (method === "GET" && /\/api\/sandboxes\/[^/]+\/files$/.test(path)) {
    return ok(mockProjectFiles) as Promise<T>;
  }
  if (method === "GET" && /\/api\/sandboxes\/[^/]+$/.test(path)) {
    return ok(sandbox(decodeURIComponent(path.split("/")[3]))) as Promise<T>;
  }

  // Unknown route: log once and resolve to an empty object so a missing mock degrades to an empty
  // screen rather than crashing the screenshot run. Add a handler above for anything that matters.
  console.warn(`[mock] Unhandled ${route} — returning empty response.`);
  return ok({} as T);
}

function repoFullNameFromPath(path: string): string {
  // /api/github/repositories/{owner}/{repo}[/branches]
  const parts = path.split("/");
  const owner = decodeURIComponent(parts[4] ?? "");
  const repo = decodeURIComponent(parts[5] ?? "");
  return `${owner}/${repo}`;
}

function parseBody(body: BodyInit | null | undefined): Record<string, unknown> {
  if (typeof body !== "string") return {};
  try {
    return JSON.parse(body) as Record<string, unknown>;
  } catch {
    return {};
  }
}
