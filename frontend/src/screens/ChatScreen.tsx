import { type ChangeEvent, type RefObject } from "react";
import { Pencil } from "lucide-react";

import type { ApprovalDecision, ChatAttachment, ChatThread, FileNode, ModelOption } from "../lib/types";
import { AppHeader } from "../components/AppHeader";
import { WorkspacePanel } from "../components/WorkspacePanel";
import { ChatPanel } from "../components/chat/ChatPanel";

/**
 * Chat surface composition: the header, the optional workspace file tree (Agent/Project modes), any
 * inline notices, and the transcript + composer. Pure presentation — all chat-engine state lives in
 * the chat session store; this screen only wires props through.
 */
export function ChatScreen(props: {
  name: string;
  showWorkspace: boolean;
  thread: ChatThread;
  files: FileNode[];
  sandboxStatus?: string;
  wsOpen: boolean;
  onToggleWs: () => void;
  error: string | null;
  bugReportNotice: string | null;
  reportingBug: boolean;
  onReportBug: () => void;
  onMenu: () => void;
  imageInputRef: RefObject<HTMLInputElement>;
  onImageSelected: (event: ChangeEvent<HTMLInputElement>) => void;
  busy: boolean;
  running: boolean;
  listRef: RefObject<HTMLDivElement>;
  draft: string;
  attachment: ChatAttachment | null;
  models: ModelOption[];
  selectedModelId?: string;
  selectedReasoning?: string;
  onDraftChange: (value: string) => void;
  onModelChange: (value: string) => void;
  onReasoningChange: (value: string) => void;
  onPickImage: () => void;
  onClearImage: () => void;
  onSend: (prompt?: string) => void;
  onStop: () => void;
  onApproval: (approvalId: string, decision: ApprovalDecision) => void;
}) {
  const { thread } = props;
  return (
    <>
      {/* Mobile header: hidden on desktop via CSS */}
      <div className="mobile-chat-header">
        <AppHeader
          onMenu={props.onMenu}
          reportAction={{ busy: props.reportingBug, onClick: props.onReportBug }}
        />
      </div>

      {/* Desktop title bar: hidden on mobile via CSS */}
      <div className="desktop-chat-header">
        <span className="desktop-chat-title">{thread.title || "New Chat"}</span>
        <button type="button" className="icon-button" aria-label="Edit title">
          <Pencil size={14} strokeWidth={2} />
        </button>
      </div>

      <input
        ref={props.imageInputRef}
        type="file"
        accept="image/*"
        onChange={props.onImageSelected}
        className="visually-hidden"
        tabIndex={-1}
      />
      {/* Workspace panel: shown in center column on mobile, hidden on desktop (shown in right panel instead) */}
      {props.showWorkspace ? (
        <div className="workspace-panel-mobile">
          <WorkspacePanel
            sessionId={thread.id}
            files={props.files}
            wsOpen={props.wsOpen}
            onToggleWs={props.onToggleWs}
            label={thread.kind === "github"
              ? `${thread.repoName ?? thread.repoFullName ?? "repo"} · ${thread.branchName ?? "branch"}`
              : "~/"}
            rootName={thread.kind === "github"
              ? (thread.repoName ?? thread.repoFullName ?? "repo")
              : "~"}
            badge={thread.kind === "github" ? "github" : "agent"}
            sandboxStatus={thread.kind === "github" ? props.sandboxStatus : undefined}
            defaultOpenDirectories={thread.kind !== "github"}
          />
        </div>
      ) : null}
      {props.error ? <p className="login-error screen-pad">{props.error}</p> : null}
      {props.bugReportNotice ? <p className="screen-note screen-pad">{props.bugReportNotice}</p> : null}
      <ChatPanel
        name={props.name}
        kind={thread.kind}
        entries={thread.entries}
        busy={props.busy}
        running={props.running}
        listRef={props.listRef}
        draft={props.draft}
        attachment={props.attachment}
        models={props.models}
        selectedModelId={props.selectedModelId}
        selectedReasoning={props.selectedReasoning}
        onDraftChange={props.onDraftChange}
        onModelChange={props.onModelChange}
        onReasoningChange={props.onReasoningChange}
        onPickImage={props.onPickImage}
        onClearImage={props.onClearImage}
        onSend={props.onSend}
        onStop={props.onStop}
        onApproval={props.onApproval}
      />
    </>
  );
}
