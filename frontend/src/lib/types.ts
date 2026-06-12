export type Route =
  | { screen: "new-chat" }
  | { screen: "history" }
  | { screen: "history-chat"; threadId: string }
  | { screen: "settings" }
  | { screen: "integrations" }
  | { screen: "google-workspace" }
  | { screen: "appearance" }
  | { screen: "data-privacy" };

export type DrawerScreen =
  | "new-chat"
  | "history"
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
  source: "history" | "draft";
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
  authMode: "oauth" | "service-account" | "none";
  configured: boolean;
  reconnectable: boolean;
  message: string;
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

export type AuthSession = {
  token: string;
  username: string;
  roles: string[];
  expiresAt: string;
};
