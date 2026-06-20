export type AuthSession = {
  token: string;
  username: string;
  roles: string[];
  expiresAt: string;
};

export type StreamToolEvent = {
  id: string;
  callId?: string;
  name: string;
  args: string;
  result: string;
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
    };

export type ChatActivity = {
  id: string;
  runId?: string;
  type: string;
  label: string;
  status: "running" | "completed" | "error" | "info";
  detail?: string;
  createdAt: string;
};

export type ChatKind = "chat" | "sandbox" | "github";

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
  connectionStatus?: "idle" | "connecting" | "open" | "reconnecting" | "error";
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
