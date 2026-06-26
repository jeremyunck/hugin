import { useCallback, useEffect, useRef, useState, type ChangeEvent } from "react";

import { createThread, getThreadTitle } from "./services/threadApi";
import { fetchModels, reportBug, saveEnabledModels } from "./services/integrationApi";
import { fetchGitHubRepository, fetchGitHubStatus } from "./services/githubApi";
import { deleteSandbox } from "./services/runApi";
import { saveAuthSession, fetchOpenRouterCredits, type OpenRouterCredits } from "./services/apiClient";
import type {
  AuthSession,
  ChatAttachment,
  ChatThread,
  GitHubRepositoryDetail,
  GitHubStatus,
  ModelOption
} from "./lib/types";
import { defaultReasoningFor } from "./lib/format";
import {
  applyFontSize,
  loadPreferences,
  resolveDefaultModelId,
  savePreferences,
  type AppPreferences
} from "./lib/preferences";
import { mostRecentThread, screenForThread, type Screen } from "./lib/screen";
import { readActiveThreadRestore, readLaunchScreen } from "./lib/launch";
import { useChatSessionStore } from "./stores/chatSessionStore";
import { useAuthBootstrap } from "./hooks/useAuthBootstrap";
import { useWorkspaceState } from "./hooks/useWorkspaceState";
import { useAgentRuns } from "./hooks/useAgentRuns";
import { useIntegrations } from "./hooks/useIntegrations";
import { useGitHubProjectSetup } from "./hooks/useGitHubProjectSetup";
import { useThreadSelection } from "./hooks/useThreadSelection";
import { useRunStream } from "./hooks/useRunStream";
import { LoginScreen } from "./screens/LoginScreen";
import { ChatScreen } from "./screens/ChatScreen";
import { IntegrationsScreen } from "./screens/IntegrationsScreen";
import { GitHubProjectSetupScreen } from "./screens/GitHubProjectSetupScreen";
import { ModelSettingsScreen } from "./screens/ModelSettingsScreen";
import { SettingsScreen } from "./screens/SettingsScreen";
import { AgentThreadsScreen } from "./screens/AgentThreadsScreen";
import { UserDetailsScreen } from "./screens/UserDetailsScreen";
import { MenuOverlay } from "./components/MenuOverlay";
import { HistoryPanel } from "./components/HistoryPanel";
import { DesktopSidebar } from "./components/desktop/DesktopSidebar";
import { DesktopProjectPanel } from "./components/desktop/DesktopProjectPanel";

// Re-exports kept stable for tests and external imports after the screen/hook refactor.
export { reduceChatEvent } from "./stores/chatEventReducer";
export { MessageList as Messages } from "./components/chat/MessageList";
export { WorkspacePanel as FileTree } from "./components/WorkspacePanel";
export { HistoryPanel as HistoryScreen } from "./components/HistoryPanel";

const MAX_IMAGE_BYTES = 5 * 1024 * 1024;

function nowIso() {
  return new Date().toISOString();
}

function sanitizeAttachmentForReport(attachment: ChatAttachment) {
  return {
    name: attachment.name,
    mimeType: attachment.mimeType,
    size: attachment.size
  };
}

function serializeThreadForBugReport(thread: ChatThread) {
  return {
    ...thread,
    entries: thread.entries.map((entry) =>
      entry.type !== "user" || !entry.attachments?.length
        ? entry
        : {
            ...entry,
            attachments: entry.attachments.map(sanitizeAttachmentForReport)
          }
    )
  };
}

/** Restores the last active thread (or the most recent one) on reload. UI convenience only. */
export function restorePreferredThread(threads: ChatThread[]) {
  const active = readActiveThreadRestore();
  if (active?.threadId) {
    const match = threads.find((thread) => thread.id === active.threadId);
    if (match) {
      return {
        thread: match,
        screen: (active.screen as Screen | undefined) ?? screenForThread(match)
      };
    }
  }
  const fallback = mostRecentThread(threads);
  if (!fallback) return null;
  return {
    thread: fallback,
    screen: screenForThread(fallback)
  };
}

export default function App() {
  const [session, setSession] = useState<AuthSession | null>(null);
  const [screen, setScreen] = useState<Screen>("login");
  const [menuOpen, setMenuOpen] = useState(false);
  const [returnScreen, setReturnScreen] = useState<Screen>("purechat");

  const store = useChatSessionStore(session?.token ?? null);
  const thread = store.activeThread;

  const [draft, setDraft] = useState("");
  const [draftAttachment, setDraftAttachment] = useState<ChatAttachment | null>(null);
  const [reportingBug, setReportingBug] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [bugReportNotice, setBugReportNotice] = useState<string | null>(null);

  const [wsOpen, setWsOpen] = useState(true);
  const [githubStatus, setGitHubStatus] = useState<GitHubStatus | null>(null);
  const [openRouterCredits, setOpenRouterCredits] = useState<OpenRouterCredits | null>(null);
  const [pendingAutoPrompt, setPendingAutoPrompt] = useState<string | null>(null);
  const [repoDetail, setRepoDetail] = useState<GitHubRepositoryDetail | null>(null);
  const [repoDetailLoading, setRepoDetailLoading] = useState(false);

  const [models, setModels] = useState<ModelOption[]>([]);
  const [savingModels, setSavingModels] = useState(false);
  const [preferences, setPreferences] = useState<AppPreferences>(() => loadPreferences());

  const bootstrappedRef = useRef(false);
  const listRef = useRef<HTMLDivElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);

  // Tracks state from the previous render to detect when the user abandons an empty github sandbox.
  const prevNavRef = useRef<{
    screen: Screen;
    sandboxId: string | undefined;
    kind: string | undefined;
    hasUserEntries: boolean;
  }>({ screen: "login", sandboxId: undefined, kind: undefined, hasUserEntries: false });

  // The workspace file tree only refreshes after an agent run finishes, so it tracks the run-busy
  // flag; the UI-level busy below additionally folds in the project-creation flag.
  const workspace = useWorkspaceState({ session, thread, screen, busy: store.activeBusy });
  const { files, setFiles, sandboxStatus, refreshFiles, refreshAgentFiles } = workspace;

  const github = useGitHubProjectSetup({
    session,
    githubStatus,
    screen,
    returnScreen,
    setReturnScreen,
    setScreen,
    setMenuOpen,
    setBugReportNotice,
    setError,
    setFiles,
    setWsOpen,
    setDraft,
    setDraftAttachment,
    setPendingAutoPrompt,
    switchThread: store.switchThread,
    refreshFiles
  });

  const busy = store.activeBusy || github.workspaceBusy;

  const integrations = useIntegrations({
    session,
    screen,
    onError: setError,
    onGitHubStatus: setGitHubStatus
  });

  const agentRuns = useAgentRuns({ session, screen, onError: setError });

  const runStream = useRunStream(store);

  const threadSelection = useThreadSelection({
    session,
    store,
    setScreen,
    setMenuOpen,
    setFiles,
    setWsOpen,
    setDraftAttachment,
    setBugReportNotice,
    setError,
    refreshFiles,
    refreshAgentFiles
  });
  const { historyQuery, setHistoryQuery, deletingThreadId, startChat, startAgent, openHistory, deleteThread } =
    threadSelection;

  const onSessionRestored = useCallback(() => {
    // The session-keyed effects below load models + GitHub status; nothing extra to do here.
  }, []);

  const onSignedIn = useCallback(() => {
    bootstrappedRef.current = true;
    store.switchThread(createThread("chat"));
    setScreen(readLaunchScreen() === "integrations" ? "integrations" : "purechat");
  }, [store]);

  const onSignedOut = useCallback(() => {
    // Re-allow bootstrap so a subsequent sign-in re-activates a thread, and clear app-level UI state.
    bootstrappedRef.current = false;
    setMenuOpen(false);
    setScreen("login");
    setDraft("");
    setDraftAttachment(null);
    setFiles([]);
    setGitHubStatus(null);
    setError(null);
    setBugReportNotice(null);
  }, [setFiles]);

  const auth = useAuthBootstrap({ setSession, onSignedIn, onSessionRestored, onSignedOut });

  // Once authenticated (via a restored session), activate a thread: the restored one or a fresh chat.
  // Sign-in performs its own activation and sets the guard so this runs at most once per session.
  useEffect(() => {
    if (!session || bootstrappedRef.current) return;
    bootstrappedRef.current = true;
    const restored = restorePreferredThread(store.threads);
    const launch = readLaunchScreen();
    if (restored) {
      store.switchThread(restored.thread);
      setScreen(launch === "integrations" ? "integrations" : restored.screen);
    } else {
      store.switchThread(createThread("chat"));
      setScreen(launch === "integrations" ? "integrations" : "purechat");
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [session]);

  useEffect(() => {
    if (!session) return;
    fetchModels(session.token)
      .then((next) => setModels(next))
      .catch(() => setModels([]));
  }, [session]);

  useEffect(() => {
    if (!session) return;
    fetchGitHubStatus(session.token)
      .then((status) => setGitHubStatus(status))
      .catch(() => setGitHubStatus(null));
  }, [session]);

  // Live OpenRouter credit balance for the sidebar usage meter.
  const refreshOpenRouterCredits = useCallback(() => {
    const token = session?.token;
    if (!token) return;
    fetchOpenRouterCredits(token)
      .then((credits) => setOpenRouterCredits(credits))
      .catch(() => setOpenRouterCredits(null));
  }, [session?.token]);

  // Refresh when the user lands back on a non-account screen, so saving/removing a key in Account
  // (or returning from it) is reflected promptly.
  useEffect(() => {
    if (screen === "user-details") return;
    refreshOpenRouterCredits();
  }, [screen, refreshOpenRouterCredits]);

  // Refresh after each agent run finishes (busy → idle): the run just spent credits invoking the
  // model, so the meter should reflect the new balance. Skipped while on the Account screen.
  const prevBusyRef = useRef(false);
  useEffect(() => {
    const wasBusy = prevBusyRef.current;
    prevBusyRef.current = store.activeBusy;
    if (wasBusy && !store.activeBusy && screen !== "user-details") {
      refreshOpenRouterCredits();
    }
  }, [store.activeBusy, screen, refreshOpenRouterCredits]);

  // Populate the desktop project panel with live GitHub metadata for the active project thread.
  const repoFullName = thread?.kind === "github" ? thread.repoFullName : undefined;
  const githubActive = githubStatus?.active === true;
  const loadRepoDetail = useCallback(() => {
    if (!session || !repoFullName || !githubActive) return;
    setRepoDetailLoading(true);
    fetchGitHubRepository(session.token, repoFullName)
      .then((detail) => setRepoDetail(detail))
      .catch(() => setRepoDetail(null))
      .finally(() => setRepoDetailLoading(false));
  }, [session, repoFullName, githubActive]);

  useEffect(() => {
    if (!session || !repoFullName || !githubActive) {
      setRepoDetail(null);
      setRepoDetailLoading(false);
      return;
    }
    setRepoDetail(null);
    loadRepoDetail();
  }, [session, repoFullName, githubActive, loadRepoDetail]);

  const updatePreferences = useCallback((partial: Partial<AppPreferences>) => {
    setPreferences((current) => {
      const next = { ...current, ...partial };
      savePreferences(next);
      return next;
    });
  }, []);

  // Apply the saved font size to the document root so the whole app scales with the preference.
  useEffect(() => {
    applyFontSize(preferences.fontSize);
  }, [preferences.fontSize]);

  // When the user navigates away from the chat screen without sending a message to a GitHub project,
  // the sandbox has been created but the thread was never persisted — delete the orphaned sandbox.
  useEffect(() => {
    const prev = prevNavRef.current;
    const leavingChat = prev.screen === "chat" && screen !== "chat";
    if (
      leavingChat &&
      session &&
      prev.kind === "github" &&
      prev.sandboxId &&
      !prev.hasUserEntries
    ) {
      deleteSandbox(session.token, prev.sandboxId).catch(() => {});
    }
    prevNavRef.current = {
      screen,
      sandboxId: thread?.sandboxId,
      kind: thread?.kind,
      hasUserEntries: thread?.entries.some((e) => e.type === "user") ?? false
    };
  }, [screen, thread, session]);

  // Keep the default model pointing at an enabled model: when it gets disabled in Model settings,
  // fall through to the next enabled model in the list (and seed a default once models first load).
  useEffect(() => {
    if (!models.length) return;
    const resolved = resolveDefaultModelId(models, preferences.defaultModelId);
    if (resolved !== preferences.defaultModelId) {
      updatePreferences({ defaultModelId: resolved });
    }
  }, [models, preferences.defaultModelId, updatePreferences]);

  // Keep scrolled to the latest message as the transcript grows.
  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = listRef.current.scrollHeight;
  }, [thread?.entries]);

  // Default the active thread's model/reasoning to an enabled model once models load.
  useEffect(() => {
    if (!thread) return;
    const enabled = models.filter((model) => model.enabled);
    if (!enabled.length) return;
    const preferredDefault = enabled.find((model) => model.id === preferences.defaultModelId) ?? enabled[0];
    const selected = enabled.find((model) => model.id === thread.modelId) ?? preferredDefault;
    const nextReasoning = thread.reasoningEffort && selected.reasoningOptions.includes(thread.reasoningEffort)
      ? thread.reasoningEffort
      : defaultReasoningFor(selected);
    if (thread.modelId === selected.id && thread.reasoningEffort === nextReasoning) return;
    store.patchThread(thread.id, { modelId: selected.id, reasoningEffort: nextReasoning });
  }, [models, thread, store, preferences.defaultModelId]);

  const goHome = useCallback(() => {
    setScreen(thread?.kind === "github" ? "chat" : "purechat");
    setMenuOpen(false);
  }, [thread]);

  const openIntegrations = useCallback(() => {
    setReturnScreen(screen === "integrations" ? returnScreen : screen);
    setScreen("integrations");
    setMenuOpen(false);
    setBugReportNotice(null);
  }, [screen, returnScreen]);

  const openSettings = useCallback(async () => {
    setReturnScreen(screen === "settings" ? returnScreen : screen);
    setScreen("settings");
    setMenuOpen(false);
    setBugReportNotice(null);
    if (!session) return;
    try {
      setModels(await fetchModels(session.token));
    } catch {
      setModels([]);
    }
  }, [screen, returnScreen, session]);

  const openPreferences = useCallback(() => {
    setReturnScreen(screen === "preferences" || screen === "settings" ? returnScreen : screen);
    setScreen("preferences");
    setMenuOpen(false);
    setBugReportNotice(null);
  }, [screen, returnScreen]);

  const openAgentThreads = useCallback(() => {
    setReturnScreen(screen === "agent-threads" ? returnScreen : screen);
    setScreen("agent-threads");
    setMenuOpen(false);
    setBugReportNotice(null);
  }, [screen, returnScreen]);

  const openUserDetails = useCallback(() => {
    setReturnScreen(screen === "user-details" ? returnScreen : screen);
    setScreen("user-details");
    setMenuOpen(false);
    setBugReportNotice(null);
  }, [screen, returnScreen]);

  const handleSessionUpdate = useCallback((updated: Partial<AuthSession>) => {
    if (!session) return;
    const next: AuthSession = { ...session, ...updated };
    setSession(next);
    saveAuthSession(next);
  }, [session]);

  const send = useCallback(
    async (textArg?: string) => {
      const text = (textArg ?? draft).trim();
      const attachment = draftAttachment;
      const current = store.activeThread;
      if ((!text && !attachment) || busy || !session || !current) return;
      const enabledModels = models.filter((model) => model.enabled);
      const preferredDefault = enabledModels.find((model) => model.id === preferences.defaultModelId) ?? enabledModels[0];
      const selectedModel = enabledModels.find((model) => model.id === current.modelId) ?? preferredDefault;
      if (!selectedModel) {
        setError("Enable at least one model in Model settings before sending a message.");
        return;
      }
      const selectedReasoning = selectedModel.reasoningOptions.includes(current.reasoningEffort ?? "")
        ? current.reasoningEffort
        : defaultReasoningFor(selectedModel);

      const sandboxId = current.sandboxId;

      setDraft("");
      setDraftAttachment(null);
      setBugReportNotice(null);
      setError(null);

      const isFirst = !current.entries.some((entry) => entry.type === "user");
      const title = isFirst
        ? getThreadTitle(text || attachment?.name || "Image attachment")
        : current.title;
      store.patchThread(current.id, {
        modelId: selectedModel.id,
        reasoningEffort: selectedReasoning,
        title,
        updatedAt: nowIso()
      });

      const mode = current.kind === "github" ? "GITHUB" : current.kind === "agent" ? "AGENT" : "CHAT";

      try {
        await store.sendMessage(current.id, {
          content: text,
          mode,
          title,
          attachments: attachment ? [attachment] : undefined,
          model: selectedModel.id,
          reasoningEffort: selectedReasoning,
          sandboxId,
          maxToolCalls: preferences.maxToolCalls,
          requestTimeoutSeconds: preferences.requestTimeoutSeconds,
          researchModel: preferences.researchModelId
        });
      } catch (e) {
        setError(e instanceof Error ? e.message : "The agent request failed.");
      } finally {
        if (current.kind === "agent") {
          void refreshAgentFiles();
        } else if (sandboxId) {
          void refreshFiles(sandboxId);
        }
      }
    },
    [draft, draftAttachment, busy, session, refreshFiles, refreshAgentFiles, models, store, preferences.defaultModelId, preferences.researchModelId, preferences.maxToolCalls, preferences.requestTimeoutSeconds]
  );

  useEffect(() => {
    if (!pendingAutoPrompt || busy) return;
    setPendingAutoPrompt(null);
    void send(pendingAutoPrompt);
  }, [pendingAutoPrompt, busy, send]);

  const saveBugReport = useCallback(async () => {
    if (!session || reportingBug) return;
    const activeThread = store.activeThread;
    if (!activeThread) return;
    setReportingBug(true);
    setError(null);
    setBugReportNotice(null);
    try {
      const response = await reportBug(session.token, {
        sessionId: activeThread.id,
        title: activeThread.title,
        sandboxId: activeThread.sandboxId,
        thread: serializeThreadForBugReport(activeThread),
        clientContext: {
          exportedAt: nowIso(),
          screen,
          url: typeof window === "undefined" ? null : window.location.href,
          userAgent: typeof navigator === "undefined" ? null : navigator.userAgent,
          busy,
          activeAssistantId: [...activeThread.entries]
            .reverse()
            .find((entry) => entry.type === "assistant" && !entry.completedAt)?.id ?? null
        }
      });
      setBugReportNotice(`Saved to ${response.relativePath}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not save bug report.");
    } finally {
      setReportingBug(false);
    }
  }, [session, reportingBug, screen, busy, store]);

  const pickImage = useCallback(() => {
    imageInputRef.current?.click();
  }, []);

  const clearImage = useCallback(() => {
    setDraftAttachment(null);
    if (imageInputRef.current) {
      imageInputRef.current.value = "";
    }
  }, []);

  const onImageSelected = useCallback(async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    if (!file.type.startsWith("image/")) {
      setError("Only image files are supported.");
      event.target.value = "";
      return;
    }
    if (file.size > MAX_IMAGE_BYTES) {
      setError("Images must be 5 MB or smaller.");
      event.target.value = "";
      return;
    }
    try {
      const dataUrl = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result ?? ""));
        reader.onerror = () => reject(new Error("Could not read the selected image."));
        reader.readAsDataURL(file);
      });
      setDraftAttachment({
        name: file.name,
        mimeType: file.type,
        dataUrl,
        size: file.size
      });
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not read the selected image.");
    } finally {
      event.target.value = "";
    }
  }, []);

  const toggleModelEnabled = useCallback((modelId: string) => {
    setModels((current) => current.map((model) => (model.id === modelId ? { ...model, enabled: !model.enabled } : model)));
  }, []);

  const saveModelPreferences = useCallback(async () => {
    if (!session || savingModels) return;
    const enabledModelIds = models.filter((model) => model.enabled).map((model) => model.id);
    if (enabledModelIds.length === 0) {
      setError("Enable at least one model before saving.");
      return;
    }
    setSavingModels(true);
    try {
      setModels(await saveEnabledModels(session.token, enabledModelIds));
      setScreen(returnScreen);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not save model settings.");
    } finally {
      setSavingModels(false);
    }
  }, [session, savingModels, models, returnScreen]);

  const enabledModels = models.filter((model) => model.enabled);
  const preferredDefaultModel = enabledModels.find((model) => model.id === preferences.defaultModelId) ?? enabledModels[0];
  const activeModel = enabledModels.find((model) => model.id === thread?.modelId) ?? preferredDefaultModel;
  const activeReasoning = activeModel?.reasoningOptions.includes(thread?.reasoningEffort ?? "")
    ? thread?.reasoningEffort
    : defaultReasoningFor(activeModel);

  const handleModelChange = useCallback(
    (modelId: string) => {
      if (!thread) return;
      const model = enabledModels.find((item) => item.id === modelId);
      store.patchThread(thread.id, { modelId, reasoningEffort: defaultReasoningFor(model) });
    },
    [thread, enabledModels, store]
  );

  if (auth.booting) {
    return (
      <div className="mock-page">
        <div className="device-shell">
          <div className="chat-body">
            <div className="greeting">
              <p>Loading…</p>
            </div>
          </div>
        </div>
      </div>
    );
  }

  const name = session?.username ?? "there";
  const isChatScreen = screen === "chat" || screen === "purechat";
  const showDesktopPanel = screen === "chat" && !!thread;

  return (
    <div className={`mock-page${session ? " desktop-has-sidebar" : ""}${showDesktopPanel ? " desktop-has-panel" : ""}`}>
      {session ? (
        <DesktopSidebar
          username={name}
          screen={screen}
          threads={store.historyThreads}
          activeThreadId={thread?.id}
          githubConnected={githubStatus?.active === true}
          onNewChat={startChat}
          onHome={goHome}
          onSearch={() => { setHistoryQuery(""); setScreen("history"); }}
          onProjects={github.openGitHubRepoSetup}
          onIntegrations={openIntegrations}
          onSettings={openPreferences}
          onThread={openHistory}
          openRouterCredits={openRouterCredits}
          onManageApiKey={openUserDetails}
        />
      ) : null}
      <div className="device-shell">
        {!session || screen === "login" ? (
          <LoginScreen
            username={auth.username}
            password={auth.password}
            error={auth.loginError}
            busy={auth.signingIn}
            onUser={auth.setUsername}
            onPass={auth.setPassword}
            onSignIn={auth.signIn}
          />
        ) : isChatScreen && thread ? (
          <ChatScreen
            name={name}
            showWorkspace={screen === "chat"}
            thread={thread}
            files={files}
            sandboxStatus={sandboxStatus}
            wsOpen={wsOpen}
            onToggleWs={() => setWsOpen((current) => !current)}
            error={error}
            bugReportNotice={bugReportNotice}
            reportingBug={reportingBug}
            onReportBug={saveBugReport}
            onMenu={() => setMenuOpen(true)}
            imageInputRef={imageInputRef}
            onImageSelected={onImageSelected}
            busy={busy}
            running={runStream.running}
            listRef={listRef}
            draft={draft}
            attachment={draftAttachment}
            models={enabledModels}
            selectedModelId={activeModel?.id}
            selectedReasoning={activeReasoning}
            onDraftChange={setDraft}
            onModelChange={handleModelChange}
            onReasoningChange={(reasoningEffort) => thread && store.patchThread(thread.id, { reasoningEffort })}
            onPickImage={pickImage}
            onClearImage={clearImage}
            onSend={send}
            onStop={runStream.stop}
            onApproval={runStream.resolveApproval}
          />
        ) : screen === "integrations" ? (
          <IntegrationsScreen
            integrations={integrations.integrations}
            loading={integrations.integrationsLoading}
            error={integrations.integrationsError}
            busyId={integrations.integrationBusy}
            onBack={() => setScreen(returnScreen)}
            onToggle={integrations.toggleIntegration}
            onReconnect={integrations.reconnectIntegration}
          />
        ) : screen === "github-repo" ? (
          <GitHubProjectSetupScreen
            busy={github.workspaceBusy}
            loadingRepos={github.loadingRepos}
            loadingBranches={github.loadingBranches}
            loadingBugReports={github.loadingBugReports}
            repositories={github.repoOptions}
            branches={github.branchOptions}
            bugReports={github.bugReports}
            selectedRepo={github.selectedRepo}
            selectedBranch={github.selectedBranch}
            selectedBugReportId={github.selectedBugReportId}
            error={error}
            onBack={() => setScreen(returnScreen)}
            onRepoChange={github.chooseRepo}
            onBranchChange={github.setSelectedBranch}
            onBugReportChange={github.setSelectedBugReportId}
            onConfirm={github.confirmGitHubRepo}
          />
        ) : screen === "settings" ? (
          <ModelSettingsScreen
            models={models}
            saving={savingModels}
            onBack={() => setScreen(returnScreen)}
            onToggle={toggleModelEnabled}
            onSave={saveModelPreferences}
          />
        ) : screen === "preferences" ? (
          <SettingsScreen
            preferences={preferences}
            enabledModels={enabledModels}
            onBack={() => setScreen(returnScreen)}
            onSave={(next) => updatePreferences(next)}
            onOpenModelSettings={openSettings}
          />
        ) : screen === "agent-threads" ? (
          <AgentThreadsScreen
            runs={agentRuns.agentRuns}
            threads={store.threads}
            busyRunId={agentRuns.agentRunBusyId}
            loading={agentRuns.agentRunsLoading}
            onBack={() => setScreen(returnScreen)}
            onCancel={agentRuns.cancelRun}
          />
        ) : screen === "user-details" && session ? (
          <UserDetailsScreen
            session={session}
            onBack={() => setScreen(returnScreen)}
            onMenu={() => setMenuOpen(true)}
            onSessionUpdate={handleSessionUpdate}
          />
        ) : (
          <HistoryPanel
            threads={store.historyThreads}
            onMenu={() => setMenuOpen(true)}
            onOpen={openHistory}
            onDelete={deleteThread}
            onNew={startChat}
            deletingId={deletingThreadId}
            query={historyQuery}
            onQuery={setHistoryQuery}
          />
        )}

        {menuOpen && session ? (
          <MenuOverlay
            username={session.username}
            displayName={session.displayName}
            roles={session.roles}
            githubConnected={githubStatus?.active === true}
            reportBusy={reportingBug}
            onReportBug={screen === "chat" || screen === "purechat" ? saveBugReport : undefined}
            onClose={() => setMenuOpen(false)}
            onAgent={startAgent}
            onGitHubRepo={github.openGitHubRepoSetup}
            onChat={startChat}
            onHistory={() => {
              setHistoryQuery("");
              setScreen("history");
              setMenuOpen(false);
            }}
            onAgentThreads={openAgentThreads}
            onIntegrations={openIntegrations}
            onSettings={openSettings}
            onPreferences={openPreferences}
            onUserDetails={openUserDetails}
            onSignOut={auth.signOut}
          />
        ) : null}
      </div>
      {session && showDesktopPanel && thread ? (
        <DesktopProjectPanel
          thread={thread}
          files={files}
          wsOpen={wsOpen}
          onToggleWs={() => setWsOpen((current) => !current)}
          sandboxStatus={sandboxStatus}
          repoDetail={repoDetail}
          repoDetailLoading={repoDetailLoading}
          onRefresh={loadRepoDetail}
        />
      ) : null}
    </div>
  );
}
