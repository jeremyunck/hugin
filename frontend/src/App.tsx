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
import { SignInScreen } from "./screens/SignInScreen";
import {
  appendAssistantReply,
  addThread,
  clearHistory,
  createThreadFromPrompt,
  disconnectGoogleWorkspace,
  fetchCurrentUser,
  fetchGoogleWorkspaceStatus,
  getThread,
  loadAuthSession,
  loadGuildState,
  login,
  reconnectGoogleWorkspace,
  saveAuthSession,
  saveGuildState,
  sendPrompt,
  setAppearanceTheme,
  setGoogleWorkspaceState,
  setReduceMotion,
  setTextSize
} from "./services/guildService";
import type { AuthSession, GuildState, Route } from "./lib/types";
import { parseHashRoute, toHash } from "./lib/routing";

export default function App() {
  const [state, setState] = useState<GuildState>(() => loadGuildState());
  const [session, setSession] = useState<AuthSession | null>(() => loadAuthSession());
  const [routeState, setRouteState] = useState(() => parseHashRoute(window.location.hash));
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [newChatDraft, setNewChatDraft] = useState("");
  const [draftByThread, setDraftByThread] = useState<Record<string, string>>({});
  const [busyThreadId, setBusyThreadId] = useState<string | null>(null);
  const [authBusy, setAuthBusy] = useState(false);
  const [initializing, setInitializing] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  useEffect(() => {
    saveGuildState(state);
  }, [state]);

  useEffect(() => {
    saveAuthSession(session);
  }, [session]);

  useEffect(() => {
    const onHashChange = () => setRouteState(parseHashRoute(window.location.hash));
    window.addEventListener("hashchange", onHashChange);
    if (!window.location.hash) window.location.hash = toHash({ screen: "new-chat" });
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  useEffect(() => {
    document.documentElement.dataset.textSize = state.appearance.textSize;
    document.documentElement.dataset.reduceMotion = state.appearance.reduceMotion ? "true" : "false";
  }, [state.appearance]);

  useEffect(() => {
    let cancelled = false;

    async function initialize() {
      if (!session?.token) {
        setInitializing(false);
        return;
      }

      try {
        const currentUser = await fetchCurrentUser(session.token);
        const googleWorkspace = await fetchGoogleWorkspaceStatus(session.token);
        if (cancelled) return;
        setSession(currentUser);
        setState((current) => setGoogleWorkspaceState(current, googleWorkspace));
      } catch (error) {
        if (cancelled) return;
        setSession(null);
        setErrorMessage(error instanceof Error ? error.message : "Could not authenticate.");
      } finally {
        if (!cancelled) setInitializing(false);
      }
    }

    void initialize();
    return () => {
      cancelled = true;
    };
  }, []);

  const route = routeState.route;
  const dialog = routeState.dialog;
  const historyThreads = useMemo(
    () => state.threads.filter((thread) => thread.messages.length > 0),
    [state.threads]
  );

  const activeThread = useMemo(() => {
    if (route.screen === "history-chat") return getThread(state, route.threadId);
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

  async function handleNewChatSend(prompt: string) {
    if (!prompt.trim() || !session?.token) return;
    const thread = createThreadFromPrompt(prompt);
    setState((current) => addThread(current, thread));
    setNewChatDraft("");
    navigate({ screen: "history-chat", threadId: thread.id });
    await sendPromptForThread(thread.id, prompt);
  }

  async function sendPromptForThread(threadId: string, prompt: string) {
    if (!prompt.trim() || !session?.token) return;
    setBusyThreadId(threadId);
    setErrorMessage(null);
    try {
      const response = await sendPrompt(session.token, threadId, prompt);
      setState((current) => appendAssistantReply(current, threadId, prompt, response));
      setDraftByThread((current) => ({ ...current, [threadId]: "" }));
    } catch (error) {
      const message = error instanceof Error ? error.message : "Request failed.";
      setErrorMessage(message);
      if ((error as { status?: number }).status === 401) {
        setSession(null);
      }
    } finally {
      setBusyThreadId(null);
    }
  }

  function updateCurrentThreadDraft(threadId: string, value: string) {
    setDraftByThread((current) => ({ ...current, [threadId]: value }));
  }

  function openClearHistory(fromRoute: Route = route) {
    navigate(fromRoute, "clear-history");
  }

  function clearHistoryCancelRoute(currentRoute: Route): Route {
    switch (currentRoute.screen) {
      case "history-chat":
      case "data-privacy":
      case "settings":
      case "history":
      case "new-chat":
        return currentRoute;
      case "integrations":
      case "google-workspace":
      case "appearance":
        return currentRoute;
    }
  }

  function clearHistoryConfirmRoute(currentRoute: Route): Route {
    return currentRoute.screen === "history-chat"
      ? { screen: "history" }
      : clearHistoryCancelRoute(currentRoute);
  }

  function confirmClearHistory() {
    setState((current) => clearHistory(current));
    window.location.hash = toHash(clearHistoryConfirmRoute(route));
  }

  async function handleSignIn(username: string, password: string) {
    setAuthBusy(true);
    setErrorMessage(null);
    try {
      const nextSession = await login(username, password);
      const googleWorkspace = await fetchGoogleWorkspaceStatus(nextSession.token);
      setSession(nextSession);
      setState((current) => setGoogleWorkspaceState(current, googleWorkspace));
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : "Sign-in failed.");
    } finally {
      setAuthBusy(false);
      setInitializing(false);
    }
  }

  function handleSignOut() {
    setSession(null);
    setErrorMessage(null);
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
        <SidebarLink label="New Chat" active={route.screen === "new-chat"} onClick={() => navigate({ screen: "new-chat" })} />
        <SidebarLink label="History" active={route.screen === "history" || route.screen === "history-chat"} onClick={() => navigate({ screen: "history" })} />
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

  if (route.screen === "new-chat") {
    content = (
      <NewChatScreen
        draft={newChatDraft}
        disabled={busyThreadId !== null}
        onDraftChange={setNewChatDraft}
        onSend={() => handleNewChatSend(newChatDraft)}
      />
    );
  } else if (route.screen === "history") {
    content = <HistoryScreen threads={historyThreads} onOpenThread={(threadId) => navigate({ screen: "history-chat", threadId })} onNavigate={navigate} />;
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
      />
    ) : null;
  } else if (route.screen === "settings") {
    content = <SettingsScreen onNavigate={navigate} onOpenClearHistory={openClearHistory} />;
  } else if (route.screen === "integrations") {
    content = <IntegrationsScreen integrations={state.integrations.list} onNavigate={navigate} />;
  } else if (route.screen === "google-workspace") {
    content = (
      <IntegrationDetailScreen
        googleWorkspace={state.integrations.googleWorkspace}
        onNavigate={navigate}
        onRefresh={async () => {
          if (!session?.token) return;
          setErrorMessage(null);
          try {
            const googleWorkspace = await fetchGoogleWorkspaceStatus(session.token);
            setState((current) => setGoogleWorkspaceState(current, googleWorkspace));
          } catch (error) {
            setErrorMessage(error instanceof Error ? error.message : "Refresh failed.");
          }
        }}
        onReconnect={async () => {
          if (!session?.token) return;
          setErrorMessage(null);
          try {
            const googleWorkspace = await reconnectGoogleWorkspace(session.token);
            setState((current) => setGoogleWorkspaceState(current, googleWorkspace));
          } catch (error) {
            setErrorMessage(error instanceof Error ? error.message : "Reconnect failed.");
          }
        }}
        onDisconnect={async () => {
          if (!session?.token) return;
          setErrorMessage(null);
          try {
            const googleWorkspace = await disconnectGoogleWorkspace(session.token);
            setState((current) => setGoogleWorkspaceState(current, googleWorkspace));
          } catch (error) {
            setErrorMessage(error instanceof Error ? error.message : "Disconnect failed.");
          }
        }}
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
        />
      );
  } else if (route.screen === "data-privacy") {
    content = (
      <DataPrivacyScreen
        state={state}
        onClearHistory={openClearHistory}
        onNavigate={navigate}
      />
    );
  }

  if (initializing) {
    return <SignInScreen busy message="Checking your Hugin session..." onSubmit={() => Promise.resolve()} />;
  }

  if (!session) {
    return <SignInScreen busy={authBusy} message={errorMessage} onSubmit={handleSignIn} />;
  }

  return (
    <Layout
      route={route}
      drawerOpen={drawerOpen}
      onOpenDrawer={() => setDrawerOpen(true)}
      onCloseDrawer={() => setDrawerOpen(false)}
      username={session.username}
      onSignOut={handleSignOut}
      onNavigate={(hash) => {
        window.location.hash = hash;
      }}
      sidebarContent={sidebar}
    >
      <div className="session-banner desktop-session-banner">
        <div>Signed in as {session.username}</div>
        <Button variant="ghost" onClick={handleSignOut}>Sign Out</Button>
      </div>
      {errorMessage ? <div className="app-alert">{errorMessage}</div> : null}
      {content}
      {dialog === "clear-history" ? (
        <ClearHistoryDialog onConfirm={confirmClearHistory} onCancel={() => (window.location.hash = toHash(clearHistoryCancelRoute(route)))} />
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
