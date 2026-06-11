import { useEffect, useMemo, useState } from "react";
import { Button } from "./components/Ui";
import { Layout } from "./components/Layout";
import { ChatScreen } from "./screens/ChatScreen";
import { HistoryScreen } from "./screens/HistoryScreen";
import { NewChatScreen } from "./screens/NewChatScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { IntegrationsScreen } from "./screens/IntegrationsScreen";
import { IntegrationDetailScreen } from "./screens/IntegrationDetailScreen";
import { AppearanceScreen } from "./screens/AppearanceScreen";
import { DataPrivacyScreen } from "./screens/DataPrivacyScreen";
import { ClearHistoryDialog } from "./screens/ClearHistoryDialog";
import { loadGuildState, saveGuildState, getThread, createBlankThread, addThread, submitPrompt, clearHistory, refreshGoogleWorkspace, reconnectGoogleWorkspace, disconnectGoogleWorkspace, setAppearanceTheme, setTextSize, setReduceMotion } from "./services/guildService";
import type { GuildState, Route } from "./lib/types";
import { parseHashRoute, toHash } from "./lib/routing";

export default function App() {
  const [state, setState] = useState<GuildState>(() => loadGuildState());
  const [routeState, setRouteState] = useState(() => parseHashRoute(window.location.hash));
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [draftByThread, setDraftByThread] = useState<Record<string, string>>({});
  const [homeDraft, setHomeDraft] = useState("");
  const [busyThreadId, setBusyThreadId] = useState<string | null>(null);

  useEffect(() => {
    saveGuildState(state);
  }, [state]);

  useEffect(() => {
    const onHashChange = () => setRouteState(parseHashRoute(window.location.hash));
    window.addEventListener("hashchange", onHashChange);
    if (!window.location.hash) window.location.hash = toHash({ screen: "chat-home" });
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.textSize = state.appearance.textSize;
    document.documentElement.dataset.reduceMotion = state.appearance.reduceMotion ? "true" : "false";
  }, [state.appearance]);

  const route = routeState.route;
  const dialog = routeState.dialog;

  const activeThread = useMemo(() => {
    if (route.screen === "history-chat") return getThread(state, route.threadId);
    if (route.screen === "check-server-status") return getThread(state, "check-server-status");
    if (route.screen === "summarize-emails") return getThread(state, "summarize-emails");
    if (route.screen === "research-ai-agents") return getThread(state, "research-ai-agents");
    return null;
  }, [route, state]);

  useEffect(() => {
    if (route.screen === "history-chat" && !activeThread && state.threads.length) {
      window.location.hash = toHash({ screen: "history" });
    }
  }, [activeThread, route.screen, state.threads.length]);

  function navigate(next: Route, nextDialog: string | null = null) {
    window.location.hash = toHash(next, nextDialog);
  }

  async function startNewChat() {
    const thread = createBlankThread();
    setState((current) => addThread(current, thread));
    setDraftByThread((current) => ({ ...current, [thread.id]: "" }));
    navigate({ screen: "history-chat", threadId: thread.id });
  }

  async function sendPromptForThread(threadId: string, prompt: string) {
    if (!prompt.trim()) return;
    setBusyThreadId(threadId);
    try {
      await new Promise((resolve) => setTimeout(resolve, 320));
      setState((current) => submitPrompt(current, threadId, prompt));
      setDraftByThread((current) => ({ ...current, [threadId]: "" }));
    } finally {
      setBusyThreadId(null);
    }
  }

  async function sendFromHome(prompt: string) {
    if (!prompt.trim()) return;
    setHomeDraft("");
    const thread = createBlankThread();
    setState((current) => addThread(current, thread));
    setDraftByThread((current) => ({ ...current, [thread.id]: "" }));
    navigate({ screen: "history-chat", threadId: thread.id });
    await sendPromptForThread(thread.id, prompt);
  }

  function updateCurrentThreadDraft(threadId: string, value: string) {
    setDraftByThread((current) => ({ ...current, [threadId]: value }));
  }

  function openClearHistory() {
    navigate({ screen: "settings" }, "clear-history");
  }

  function confirmClearHistory() {
    setState((current) => clearHistory(current));
    window.location.hash = toHash({ screen: "history" });
  }

  useEffect(() => {
    if (route.screen !== "settings" || dialog !== "clear-history") return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") window.location.hash = toHash({ screen: "settings" });
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [dialog, route.screen]);

  const sidebar = (
    <div className="sidebar-stack">
      <nav className="sidebar-nav">
        <SidebarLink label="Chat Home" active={route.screen === "chat-home"} onClick={() => navigate({ screen: "chat-home" })} />
        <SidebarLink label="New Chat" active={route.screen === "new-chat"} onClick={() => navigate({ screen: "new-chat" })} />
        <SidebarLink label="History" active={route.screen === "history" || route.screen === "history-chat"} onClick={() => navigate({ screen: "history" })} />
      </nav>

      <nav className="sidebar-nav">
        <SidebarSection title="Suggested prompts" />
        <SidebarLink label="Check Server Status" active={route.screen === "check-server-status"} onClick={() => navigate({ screen: "check-server-status" })} />
        <SidebarLink label="Summarize Emails" active={route.screen === "summarize-emails"} onClick={() => navigate({ screen: "summarize-emails" })} />
        <SidebarLink label="Research on AI Agents" active={route.screen === "research-ai-agents"} onClick={() => navigate({ screen: "research-ai-agents" })} />
      </nav>

      <nav className="sidebar-nav">
        <SidebarSection title="Settings" />
        <SidebarLink label="Overview" active={route.screen === "settings"} onClick={() => navigate({ screen: "settings" })} />
        <SidebarLink label="Integrations" active={route.screen === "integrations" || route.screen === "google-workspace"} onClick={() => navigate({ screen: "integrations" })} />
        <SidebarLink label="Appearance" active={route.screen === "appearance"} onClick={() => navigate({ screen: "appearance" })} />
        <SidebarLink label="Data & Privacy" active={route.screen === "data-privacy"} onClick={() => navigate({ screen: "data-privacy" })} />
      </nav>
    </div>
  );

  let content = null;

  if (route.screen === "chat-home") {
    content = (
      <ChatScreen
        title="Hugin"
        subtitle="How can I help?"
        thread={null}
        isHome
        draft={homeDraft}
        disabled={busyThreadId !== null}
        onDraftChange={setHomeDraft}
        onSend={() => void sendFromHome(homeDraft)}
        onNavigate={(next) => navigate(next)}
        onStartNewChat={startNewChat}
        onOpenDrawer={() => setDrawerOpen(true)}
      />
    );
  } else if (route.screen === "new-chat") {
    content = <NewChatScreen onNavigate={navigate} onStartNewChat={startNewChat} onOpenDrawer={() => setDrawerOpen(true)} />;
  } else if (route.screen === "history") {
    content = <HistoryScreen threads={state.threads} onOpenThread={(threadId) => navigate({ screen: "history-chat", threadId })} onNavigate={navigate} onOpenDrawer={() => setDrawerOpen(true)} />;
  } else if (route.screen === "history-chat") {
    content = activeThread ? (
      <ChatScreen
        title={activeThread.title}
        subtitle="Saved conversation"
        thread={activeThread}
        draft={draftByThread[activeThread.id] || ""}
        disabled={busyThreadId === activeThread.id}
        onDraftChange={(value) => updateCurrentThreadDraft(activeThread.id, value)}
        onSend={() => void sendPromptForThread(activeThread.id, draftByThread[activeThread.id] || "")}
        onNavigate={navigate}
        onStartNewChat={startNewChat}
        onOpenDrawer={() => setDrawerOpen(true)}
      />
    ) : null;
  } else if (route.screen === "check-server-status" || route.screen === "summarize-emails" || route.screen === "research-ai-agents") {
    const titles = {
      "check-server-status": "Check Server Status",
      "summarize-emails": "Summarize Emails",
      "research-ai-agents": "Research on AI Agents"
    } as const;
    const thread = activeThread;
    content = thread ? (
      <ChatScreen
        title={titles[route.screen]}
        subtitle="Prompt-driven chat"
        thread={thread}
        draft={draftByThread[thread.id] || ""}
        disabled={busyThreadId === thread.id}
        onDraftChange={(value) => updateCurrentThreadDraft(thread.id, value)}
        onSend={() => void sendPromptForThread(thread.id, draftByThread[thread.id] || "")}
        onNavigate={navigate}
        onStartNewChat={startNewChat}
        onOpenDrawer={() => setDrawerOpen(true)}
      />
    ) : null;
  } else if (route.screen === "settings") {
    content = <SettingsScreen onNavigate={navigate} onOpenClearHistory={openClearHistory} onOpenDrawer={() => setDrawerOpen(true)} />;
  } else if (route.screen === "integrations") {
    content = <IntegrationsScreen integrations={state.integrations.list} onNavigate={navigate} onOpenDrawer={() => setDrawerOpen(true)} />;
  } else if (route.screen === "google-workspace") {
    content = (
      <IntegrationDetailScreen
        googleWorkspace={state.integrations.googleWorkspace}
        onNavigate={navigate}
        onRefresh={() => setState((current) => refreshGoogleWorkspace(current))}
        onReconnect={() => setState((current) => reconnectGoogleWorkspace(current))}
        onDisconnect={() => setState((current) => disconnectGoogleWorkspace(current))}
        onOpenDrawer={() => setDrawerOpen(true)}
      />
    );
  } else if (route.screen === "appearance") {
    content = (
        <AppearanceScreen
          appearance={state.appearance}
          onThemeChange={(theme) => setState((current) => setAppearanceTheme(current, theme))}
          onTextSizeChange={(size) => setState((current) => setTextSize(current, size))}
          onReduceMotionChange={(value) => setState((current) => setReduceMotion(current, value))}
          onNavigate={navigate}
          onOpenDrawer={() => setDrawerOpen(true)}
        />
      );
  } else if (route.screen === "data-privacy") {
    content = (
      <DataPrivacyScreen
        state={state}
        onClearHistory={openClearHistory}
        onNavigate={navigate}
        onOpenDrawer={() => setDrawerOpen(true)}
      />
    );
  }

  return (
    <Layout
      route={route}
      drawerOpen={drawerOpen}
      onOpenDrawer={() => setDrawerOpen(true)}
      onCloseDrawer={() => setDrawerOpen(false)}
      onNavigate={(hash) => {
        window.location.hash = hash;
      }}
      sidebarContent={sidebar}
    >
      {content}
      {dialog === "clear-history" ? (
        <ClearHistoryDialog onConfirm={confirmClearHistory} onCancel={() => (window.location.hash = toHash({ screen: "settings" }))} />
      ) : null}
    </Layout>
  );
}

function SidebarLink({
  label,
  active,
  onClick
}: {
  label: string;
  active?: boolean;
  onClick: () => void;
}) {
  return (
    <Button variant={active ? "ghost" : "ghost"} className={`sidebar-link ${active ? "active" : ""}`} onClick={onClick}>
      {label}
    </Button>
  );
}

function SidebarSection({ title }: { title: string }) {
  return <div className="sidebar-section">{title}</div>;
}
