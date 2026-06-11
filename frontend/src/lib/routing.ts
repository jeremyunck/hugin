import type { Route } from "./types";

function cleanHash(value: string) {
  const trimmed = value.replace(/^#/, "");
  if (!trimmed || trimmed === "/") return "/chat";
  return trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
}

export function parseHashRoute(hash: string): { route: Route; dialog: string | null } {
  const raw = cleanHash(hash);
  const [path, search = ""] = raw.split("?");
  const params = new URLSearchParams(search);
  const dialog = params.get("dialog");

  switch (path) {
    case "/":
    case "/chat":
      return { route: { screen: "chat-home" }, dialog };
    case "/chat/new":
      return { route: { screen: "new-chat" }, dialog };
    case "/chat/check-server-status":
      return { route: { screen: "check-server-status" }, dialog };
    case "/chat/summarize-emails":
      return { route: { screen: "summarize-emails" }, dialog };
    case "/chat/research-ai-agents":
      return { route: { screen: "research-ai-agents" }, dialog };
    case "/history":
      return { route: { screen: "history" }, dialog };
    case "/settings":
      return { route: { screen: "settings" }, dialog };
    case "/settings/integrations":
      return { route: { screen: "integrations" }, dialog };
    case "/settings/integrations/google-workspace":
      return { route: { screen: "google-workspace" }, dialog };
    case "/settings/appearance":
      return { route: { screen: "appearance" }, dialog };
    case "/settings/data-privacy":
      return { route: { screen: "data-privacy" }, dialog };
    default:
      if (path.startsWith("/history/")) {
        return { route: { screen: "history-chat", threadId: decodeURIComponent(path.slice("/history/".length)) }, dialog };
      }
      return { route: { screen: "chat-home" }, dialog };
  }
}

export function toHash(route: Route, dialog: string | null = null) {
  let path = "/chat";
  switch (route.screen) {
    case "chat-home":
      path = "/chat";
      break;
    case "new-chat":
      path = "/chat/new";
      break;
    case "history":
      path = "/history";
      break;
    case "history-chat":
      path = `/history/${encodeURIComponent(route.threadId)}`;
      break;
    case "check-server-status":
      path = "/chat/check-server-status";
      break;
    case "summarize-emails":
      path = "/chat/summarize-emails";
      break;
    case "research-ai-agents":
      path = "/chat/research-ai-agents";
      break;
    case "settings":
      path = "/settings";
      break;
    case "integrations":
      path = "/settings/integrations";
      break;
    case "google-workspace":
      path = "/settings/integrations/google-workspace";
      break;
    case "appearance":
      path = "/settings/appearance";
      break;
    case "data-privacy":
      path = "/settings/data-privacy";
      break;
  }

  const params = new URLSearchParams();
  if (dialog) params.set("dialog", dialog);
  const query = params.toString();
  return `#${path}${query ? `?${query}` : ""}`;
}
