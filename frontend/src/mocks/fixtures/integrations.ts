import type { Integration } from "../../lib/types";

/**
 * Integrations card list. GitHub and Google (Gmail/Calendar) are connected; Slack is available but
 * not yet connected, so the screen shows both connected and reconnectable states.
 */
export const mockIntegrations: Integration[] = [
  {
    id: "github",
    name: "GitHub",
    description: "Clone repositories into an isolated sandbox so the agent can read, edit, and open pull requests.",
    connected: true,
    reconnectable: true,
    authMode: "github_app",
    tools: ["list_repositories", "read_file", "write_file", "open_pull_request", "list_branches"],
    message: "Connected as @ada-lovelace."
  },
  {
    id: "google",
    name: "Google Workspace",
    description: "Let the agent read and triage Gmail and reference your Calendar with explicit approval for destructive actions.",
    connected: true,
    reconnectable: true,
    authMode: "oauth",
    tools: ["gmail_search", "gmail_read", "gmail_trash", "calendar_list_events"],
    message: "Connected as ada@bouw.dev."
  },
  {
    id: "slack",
    name: "Slack",
    description: "Post run summaries to a channel and let teammates kick off agent threads from Slack.",
    connected: false,
    reconnectable: false,
    authMode: "oauth",
    tools: ["slack_post_message", "slack_list_channels"],
    message: "Not connected."
  }
];
