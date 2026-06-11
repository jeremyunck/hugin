export type Route =
  | { screen: "chat-home" }
  | { screen: "new-chat" }
  | { screen: "history" }
  | { screen: "history-chat"; threadId: string }
  | { screen: "check-server-status" }
  | { screen: "summarize-emails" }
  | { screen: "research-ai-agents" }
  | { screen: "settings" }
  | { screen: "integrations" }
  | { screen: "google-workspace" }
  | { screen: "appearance" }
  | { screen: "data-privacy" };

export type DrawerScreen =
  | "chat-home"
  | "new-chat"
  | "history"
  | "check-server-status"
  | "summarize-emails"
  | "research-ai-agents"
  | "settings"
  | "integrations"
  | "appearance"
  | "data-privacy";

export type ChatRole = "user" | "assistant";

export type ChatMessage = {
  id: string;
  role: ChatRole;
  content: string;
  createdAt: string;
};

export type ChatThread = {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  messages: ChatMessage[];
  source: "home" | "scenario" | "history" | "draft";
};

export type AppearanceSettings = {
  theme: "light";
  textSize: "small" | "medium" | "large";
  reduceMotion: boolean;
};

export type ConnectedServiceStatus = "connected" | "attention" | "not-connected";

export type IntegrationItem = {
  id: string;
  label: string;
  subtitle: string;
  status: ConnectedServiceStatus;
  detail: string;
};

export type GoogleWorkspaceState = {
  accountName: string;
  authStatus: "connected" | "attention" | "not-connected";
  lastRefreshedAt: string;
  connectedServices: Array<{
    label: string;
    status: ConnectedServiceStatus;
  }>;
};

export type GuildState = {
  threads: ChatThread[];
  appearance: AppearanceSettings;
  integrations: {
    list: IntegrationItem[];
    googleWorkspace: GoogleWorkspaceState;
  };
};
