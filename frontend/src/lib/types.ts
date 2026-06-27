export type AuthSession = {
  token: string;
  username: string;
  roles: string[];
  expiresAt: string;
  displayName?: string | null;
  email?: string | null;
  customInstructions?: string | null;
};

export type StreamToolEvent = {
  id: string;
  callId?: string;
  name: string;
  args: string;
  result: string;
  /** True when the tool call finished with an error rather than a normal result. */
  error?: boolean;
  startedAt: string;
  finishedAt?: string;
};

export type ChatAttachment = {
  name: string;
  mimeType: string;
  dataUrl?: string;
  size?: number;
};

export type ChatEntry =
  | {
      id: string;
      type: "user";
      content: string;
      attachments?: ChatAttachment[];
      createdAt: string;
      /**
       * Marks a client-generated optimistic draft that has not yet been confirmed by a
       * backend `user_message_created` event. This is the only place a frontend id is allowed
       * to back a chat message; it is reconciled to the backend `messageId` on confirmation.
       */
      pending?: boolean;
    }
  | {
      id: string;
      type: "assistant";
      content: string;
      reasoning: string;
      createdAt: string;
      completedAt?: string;
    }
  | {
      id: string;
      type: "tool";
      tool: StreamToolEvent;
      createdAt: string;
    }
  | {
      /** An inline system notice in the transcript, e.g. that the conversation was compacted. */
      id: string;
      type: "notice";
      content: string;
      createdAt: string;
    }
  | {
      /**
       * A pending action that needs the user's explicit approval (e.g. deleting emails). Rendered as
       * an approve/decline card; the backend only performs the action once the user approves. Rebuilt
       * verbatim from the replayed `approval_required` event so a reconnecting client shows the prompt.
       */
      id: string;
      type: "approval";
      approvalId: string;
      kind: string;
      summary: string;
      items: ApprovalItem[];
      status: "pending" | "approved" | "declined";
      /** Outcome text once resolved, e.g. "Moved 2 emails to Trash." */
      resultText?: string;
      createdAt: string;
    };

/** One row of an approval prompt. For email deletion this is a message preview. */
export type ApprovalItem = {
  id?: string;
  from?: string;
  subject?: string;
  snippet?: string;
};

export type ApprovalDecision = "approve" | "decline";

export type ChatActivity = {
  id: string;
  runId?: string;
  type: string;
  label: string;
  status: "running" | "completed" | "error" | "info";
  detail?: string;
  createdAt: string;
};

export type ChatKind = "chat" | "agent" | "github";

export type ConnectionStatus = "idle" | "connecting" | "open" | "reconnecting" | "error";

export type RunStatus =
  | "queued"
  | "running"
  | "cancelling"
  | "awaiting_approval"
  | "completed"
  | "failed";

/** Per-thread run state, derived entirely from backend run_* events plus optimistic send. */
export type ChatRun = {
  id: string | null;
  status: RunStatus;
  startedAt?: string;
  completedAt?: string;
  error?: string;
};

export type ChatMessage = {
  role: "user" | "assistant";
  content: string;
  attachments?: ChatAttachment[];
  reasoning_content?: string;
};

export type ChatThread = {
  id: string;
  title: string;
  kind: ChatKind;
  sandboxId?: string;
  repoFullName?: string;
  repoName?: string;
  branchName?: string;
  modelId?: string;
  reasoningEffort?: string;
  createdAt: string;
  updatedAt: string;
  entries: ChatEntry[];
  activities?: ChatActivity[];
  lastSeq?: number;
  /** Active/last run for this thread. Drives send/stop/loading state. */
  run?: ChatRun | null;
  connectionStatus?: ConnectionStatus;
  /**
   * True once the thread has accepted at least one message and therefore has server-side history.
   * Drives whether the thread appears in History; transient empty "New chat" drafts stay false so
   * they don't accumulate as clutter across reloads.
   */
  hasHistory?: boolean;
};

export type AppState = {
  threads: ChatThread[];
};

export type SandboxInfo = {
  id: string;
  containerName: string;
  image: string;
  status: string;
  createdAt: string;
  workspace: string;
};

export type FileNode = {
  name: string;
  path: string;
  type: "file" | "dir";
  size?: number;
  children?: FileNode[];
};

export type ToolSummary = {
  name: string;
  description: string;
  server: string;
  transport: string;
};

export type Integration = {
  id: string;
  name: string;
  description: string;
  connected: boolean;
  reconnectable: boolean;
  authMode: string;
  tools: string[];
  message: string;
};

/** A tool discovered from an MCP server. Mirrors the backend McpToolDto. */
export type McpTool = {
  id: string;
  toolName: string;
  huginToolName: string;
  description: string | null;
  enabled: boolean;
  stale: boolean;
  lastSeenAt: string | null;
};

/** A user-connected MCP server. Mirrors the backend McpServerDto — never carries any secret. */
export type McpServer = {
  id: string;
  name: string;
  displayName: string;
  transport: string;
  endpointUrl: string | null;
  authType: string;
  enabled: boolean;
  hasToken: boolean;
  oauthConnected: boolean;
  needsAuthorization: boolean;
  command: string | null;
  toolCount: number;
  enabledToolCount: number;
  createdAt: string;
  updatedAt: string;
  tools: McpTool[];
};

/** A curated MCP server users can add with one click. Mirrors the backend McpCatalogEntry. */
export type McpCatalogEntry = {
  id: string;
  name: string;
  description: string;
  suggestedServerName: string;
  transport: string;
  endpointUrl: string;
  authType: string;
  docsUrl: string;
};

export type McpTestResult = {
  success: boolean;
  message: string;
  serverName: string | null;
  serverVersion: string | null;
  protocolVersion: string | null;
};

export type McpDiscoveryResult = {
  success: boolean;
  message: string;
  discoveredCount: number;
  tools: McpTool[];
};

export type GitHubStatus = {
  active: boolean;
  configured: boolean;
  reconnectable: boolean;
  authMode: string;
  account: string;
  message: string;
};

export type GitHubRepository = {
  fullName: string;
  name: string;
  owner: string;
  privateRepo: boolean;
  defaultBranch: string;
  description: string;
};

export type GitHubBranch = {
  name: string;
};

/** Richer metadata for a single repository, used to populate the desktop project panel. */
export type GitHubRepositoryDetail = {
  fullName: string;
  name: string;
  owner: string;
  privateRepo: boolean;
  defaultBranch: string;
  description: string | null;
  language: string | null;
  stargazers: number;
  forks: number;
  openIssues: number;
  htmlUrl: string | null;
  pushedAt: string | null;
};

export type ModelOption = {
  id: string;
  name: string;
  description?: string | null;
  contextLength?: number | null;
  promptPrice?: string | null;
  completionPrice?: string | null;
  reasoningOptions: string[];
  enabled: boolean;
};

export type BugReportResponse = {
  id?: string | null;
  relativePath: string;
  logFiles: string[];
};

export type BugReportSummary = {
  id: string;
  title: string;
  relativePath: string;
  createdAt: string;
};

export type AgentRun = {
  id: string;
  owner: string;
  sessionId?: string | null;
  agentId?: string | null;
  prompt: string;
  model?: string | null;
  sandboxId?: string | null;
  startedAt: string;
  disconnected: boolean;
  cancellationRequested: boolean;
};
