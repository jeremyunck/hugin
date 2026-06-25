// Compatibility barrel.
//
// The former monolithic guildService was split into focused service modules during the reliability
// refactor:
//   - apiClient.ts      — shared HTTP wrapper, auth/session storage, ChatEvent type, primitives
//   - threadApi.ts      — threads, chat sessions, messages, events, history, SSE stream
//   - runApi.ts         — agent runs, sandboxes, workspace files, tools
//   - integrationApi.ts — integrations, Google, models, bug reports
//   - githubApi.ts      — GitHub connect/status/repositories/branches
//
// New code should import from the specific module. This barrel re-exports the full surface so
// existing imports (and tests) that reference "services/guildService" keep working unchanged.

export type { ChatEvent } from "./apiClient";
export {
  apiFetch,
  delay,
  errorFromResponse,
  fetchCurrentUser,
  formatTimestamp,
  loadAuthSession,
  login,
  nowIso,
  saveAuthSession,
  uid
} from "./apiClient";

export type {
  ChatEventStreamHandlers,
  SendChatMessageOptions,
  SendChatMessageResponse,
  ServerChatMessage
} from "./threadApi";
export {
  addThread,
  appendAssistantDelta,
  appendEntries,
  appendReasoningDelta,
  appendToolCall,
  attachToolResult,
  buildAssistantEntry,
  buildPriorMessages,
  buildUserEntry,
  cancelChatRun,
  completeAssistantEntry,
  createEmptyState,
  createThread,
  deleteThreadHistory,
  fetchChatSessionEvents,
  getThreadTitle,
  loadAppState,
  openChatEventStream,
  rebuildThreadFromHistory,
  recoverThreadAfterDroppedStream,
  removeThread,
  resolveChatApproval,
  saveAppState,
  sendChatMessage,
  syncThreadHistory,
  updateThread
} from "./threadApi";

export {
  cancelAgentRun,
  createGitHubSandbox,
  deleteSandbox,
  fetchAgentRuns,
  fetchAgentWorkspaceFiles,
  fetchSandbox,
  fetchSandboxFiles,
  fetchTools
} from "./runApi";

export {
  disconnectGoogle,
  fetchBugReports,
  fetchIntegrations,
  fetchModels,
  reconnectGoogle,
  reportBug,
  saveEnabledModels
} from "./integrationApi";

export {
  connectGitHub,
  disconnectGitHub,
  fetchGitHubBranches,
  fetchGitHubRepositories,
  fetchGitHubStatus
} from "./githubApi";
