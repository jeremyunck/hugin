export type AuthSession = {
  token: string;
  username: string;
  roles: string[];
  expiresAt: string;
};

export type StreamToolEvent = {
  id: string;
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

export type ChatKind = "chat" | "sandbox";

export type ChatThread = {
  id: string;
  title: string;
  kind: ChatKind;
  sandboxId?: string;
  createdAt: string;
  updatedAt: string;
  entries: ChatEntry[];
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
  showActionWhenDisconnected: boolean;
  authMode: string;
  tools: string[];
  message: string;
};
