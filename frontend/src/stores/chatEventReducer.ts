import type { ApprovalItem, ChatActivity, ChatAttachment, ChatEntry, ChatRun, ChatThread } from "../lib/types";
import type { ChatEvent } from "../services/guildService";

/**
 * Tool-call events are projected inline into the chat transcript as expandable tool entries, so the
 * model's tool use reads in chronological order alongside its messages. The compaction marker becomes
 * an inline notice. Everything else (run lifecycle, misc status) stays in the activity projection.
 */
const TOOL_EVENT_TYPES = new Set<string>([
  "tool_call_started",
  "tool_call_completed",
  "tool_call_error"
]);

/**
 * Pure, idempotent projection of the backend chat event log into a {@link ChatThread}.
 *
 * Invariant: the same ordered event list MUST produce the exact same UI projection whether it
 * arrives from `GET /events` or live through SSE. Achieving this requires that every reduction
 * be a deterministic function of (thread, event) with no reliance on wall-clock or insertion path.
 *
 * Deduplication: events are keyed by their backend `seq`, which is monotonically increasing and
 * unique per session. Re-applying an event whose seq is not strictly greater than the highest seq
 * already folded into the thread is a no-op. Structural events (messages) are additionally keyed by
 * `messageId` so a re-applied "started" event cannot create a second bubble. Together this makes
 * the reducer idempotent under duplicate delivery (fetch + stream overlap, SSE retries, etc.).
 *
 * Projection boundary:
 *   - Chat projection (`entries`): user + assistant natural-language messages only.
 *   - Activity projection (`activities`): tool calls, run lifecycle, and any other low-level /
 *     status / debug events. Tool output never enters the main chat.
 */

const CHAT_EVENT_TYPES = new Set<string>([
  "user_message_created",
  "assistant_message_started",
  "assistant_token",
  "assistant_reasoning",
  "assistant_message_completed",
  "assistant_message_error"
]);

/**
 * Classifier for the chat/activity boundary.
 *
 * Chat-stream events — user/assistant messages, tool calls, and the compaction notice — are projected
 * inline into the transcript. Everything else (run lifecycle, misc status/debug) is activity.
 */
export function isActivityEvent(type: string): boolean {
  return !CHAT_EVENT_TYPES.has(type) && !isInlineEntryEvent(type);
}

/** Approval-flow events are projected inline (an approve/decline card) and also drive run state. */
const APPROVAL_EVENT_TYPES = new Set<string>(["approval_required", "approval_resolved"]);

/** Whether an event is projected inline into the chat transcript rather than the activity list. */
export function isInlineEntryEvent(type: string): boolean {
  return TOOL_EVENT_TYPES.has(type) || type === "conversation_compacted" || APPROVAL_EVENT_TYPES.has(type);
}

function metadataString(event: ChatEvent, key: string): string | undefined {
  const raw = event.metadata?.[key];
  return typeof raw === "string" && raw ? raw : undefined;
}

function activityLabel(event: ChatEvent): string {
  const name = metadataString(event, "name") ?? event.type;
  switch (event.type) {
    case "run_started":
      return "Run started";
    case "run_completed":
      return "Run completed";
    case "run_error":
      return "Run failed";
    case "tool_call_started":
      return `Tool started: ${name}`;
    case "tool_call_completed":
      return `Tool completed: ${name}`;
    case "tool_call_error":
      return `Tool failed: ${name}`;
    default:
      return event.type.replaceAll("_", " ");
  }
}

function activityDetail(event: ChatEvent): string | undefined {
  return (
    metadataString(event, "result")
    ?? metadataString(event, "args")
    ?? metadataString(event, "message")
    ?? event.content
    ?? undefined
  );
}

function activityStatus(event: ChatEvent): ChatActivity["status"] {
  if (event.type.endsWith("_error") || event.type === "run_error") return "error";
  if (event.type.endsWith("_completed") || event.type === "run_completed") return "completed";
  if (event.type.endsWith("_started") || event.type === "run_started") return "running";
  return "info";
}

function buildActivity(event: ChatEvent): ChatActivity {
  return {
    id: event.id,
    runId: event.runId ?? undefined,
    type: event.type,
    label: activityLabel(event),
    status: activityStatus(event),
    detail: activityDetail(event),
    createdAt: event.createdAt
  };
}

function reduceRun(run: ChatRun | null, event: ChatEvent): ChatRun | null {
  const runId = event.runId ?? run?.id ?? null;
  switch (event.type) {
    case "run_started":
      return { id: runId, status: "running", startedAt: event.createdAt };
    case "run_completed":
      return { ...(run ?? { id: runId }), id: runId, status: "completed", completedAt: event.createdAt };
    case "run_error":
      return {
        ...(run ?? { id: runId }),
        id: runId,
        status: "failed",
        completedAt: event.createdAt,
        error: metadataString(event, "message") ?? event.content ?? "The run failed."
      };
    case "approval_required":
      // The run is parked waiting for the user; not busy, but not finished either.
      return { ...(run ?? { id: runId }), id: runId, status: "awaiting_approval" };
    case "approval_resolved":
      return { ...(run ?? { id: runId }), id: runId, status: "completed", completedAt: event.createdAt };
    default:
      return run;
  }
}

function asAssistant(entry: ChatEntry | undefined): Extract<ChatEntry, { type: "assistant" }> | null {
  return entry?.type === "assistant" ? entry : null;
}

/**
 * Projects a tool-call event into an inline tool entry. `tool_call_started` opens the entry (keyed by
 * its backend call id so the matching result can find it); `tool_call_completed` / `tool_call_error`
 * fill in the result and mark it finished. Out-of-order/duplicate delivery is tolerated: a result with
 * no prior start still materializes an entry, and a re-applied start is a no-op.
 */
function reduceToolEntry(entries: ChatEntry[], event: ChatEvent): ChatEntry[] {
  const callId = metadataString(event, "callId");
  const name = metadataString(event, "name") ?? "tool";
  const next = entries.slice();
  const index = next.findIndex(
    (entry) => entry.type === "tool" && (callId ? entry.tool.callId === callId : entry.id === event.id)
  );

  if (event.type === "tool_call_started") {
    if (index !== -1) return entries; // idempotent re-delivery
    next.push({
      id: event.id,
      type: "tool",
      tool: {
        id: event.id,
        callId,
        name,
        args: metadataString(event, "args") ?? "",
        result: "",
        startedAt: event.createdAt
      },
      createdAt: event.createdAt
    });
    return next;
  }

  // tool_call_completed / tool_call_error
  const result = metadataString(event, "result") ?? metadataString(event, "message") ?? event.content ?? "";
  const isError = event.type === "tool_call_error";
  if (index === -1) {
    next.push({
      id: event.id,
      type: "tool",
      tool: { id: event.id, callId, name, args: "", result, error: isError, startedAt: event.createdAt, finishedAt: event.createdAt },
      createdAt: event.createdAt
    });
    return next;
  }
  const existing = next[index] as Extract<ChatEntry, { type: "tool" }>;
  next[index] = { ...existing, tool: { ...existing.tool, result, error: isError, finishedAt: event.createdAt } };
  return next;
}

/** Projects a `conversation_compacted` event into an inline notice entry. */
function reduceNoticeEntry(entries: ChatEntry[], event: ChatEvent): ChatEntry[] {
  if (entries.some((entry) => entry.type === "notice" && entry.id === event.id)) {
    return entries;
  }
  return [
    ...entries,
    {
      id: event.id,
      type: "notice",
      content: event.content ?? "Conversation compacted to fit the model's context window.",
      createdAt: event.createdAt
    }
  ];
}

/**
 * Projects the approval flow into an inline approve/decline card. `approval_required` opens the card
 * (keyed by its `approvalId` so a replay can't duplicate it); `approval_resolved` flips the matching
 * card to its decided state and records the outcome text.
 */
function reduceApprovalEntry(entries: ChatEntry[], event: ChatEvent): ChatEntry[] {
  const approvalId = metadataString(event, "approvalId");
  const next = entries.slice();

  if (event.type === "approval_required") {
    if (approvalId && next.some((entry) => entry.type === "approval" && entry.approvalId === approvalId)) {
      return entries; // idempotent re-delivery
    }
    const rawItems = event.metadata?.items;
    const items = Array.isArray(rawItems) ? (rawItems as ApprovalItem[]) : [];
    next.push({
      id: event.id,
      type: "approval",
      approvalId: approvalId ?? event.id,
      kind: metadataString(event, "kind") ?? "approval",
      summary: event.content ?? "This action needs your approval.",
      items,
      status: "pending",
      createdAt: event.createdAt
    });
    return next;
  }

  // approval_resolved
  const index = next.findIndex((entry) => entry.type === "approval" && entry.approvalId === approvalId);
  if (index === -1) return entries;
  const existing = next[index] as Extract<ChatEntry, { type: "approval" }>;
  const decision = metadataString(event, "decision");
  next[index] = {
    ...existing,
    status: decision === "approved" ? "approved" : "declined",
    resultText: event.content ?? existing.resultText
  };
  return next;
}

function reduceMessageEntry(entries: ChatEntry[], event: ChatEvent): ChatEntry[] {
  const messageId = event.messageId ?? undefined;
  const next = entries.slice();
  const index = messageId ? next.findIndex((entry) => entry.id === messageId) : -1;
  const eventAttachments = Array.isArray(event.metadata?.attachments)
    ? (event.metadata?.attachments as ChatAttachment[])
    : undefined;

  switch (event.type) {
    case "user_message_created": {
      if (!messageId || index !== -1) break;
      // Reconcile an optimistic pending draft (frontend id) into the confirmed backend message.
      const pendingIndex = next.findIndex(
        (entry) => entry.type === "user" && entry.pending && entry.content === (event.content ?? "")
      );
      const pending = pendingIndex !== -1 ? next[pendingIndex] : undefined;
      const attachments = eventAttachments?.length
        ? eventAttachments
        : pending?.type === "user"
          ? pending.attachments
          : undefined;
      const entry: ChatEntry = {
        id: messageId,
        type: "user",
        content: event.content ?? "",
        ...(attachments?.length ? { attachments } : {}),
        createdAt: event.createdAt
      };
      if (pendingIndex !== -1) {
        next[pendingIndex] = entry;
      } else {
        next.push(entry);
      }
      break;
    }
    case "assistant_message_started": {
      if (messageId && index === -1) {
        next.push({ id: messageId, type: "assistant", content: "", reasoning: "", createdAt: event.createdAt });
      }
      break;
    }
    case "assistant_reasoning": {
      if (!messageId) break;
      const current = asAssistant(next[index]);
      if (index === -1) {
        next.push({ id: messageId, type: "assistant", content: "", reasoning: event.content ?? "", createdAt: event.createdAt });
      } else if (current) {
        next[index] = { ...current, reasoning: `${current.reasoning}${event.content ?? ""}` };
      }
      break;
    }
    case "assistant_token": {
      if (!messageId) break;
      const current = asAssistant(next[index]);
      if (index === -1) {
        next.push({ id: messageId, type: "assistant", content: event.content ?? "", reasoning: "", createdAt: event.createdAt });
      } else if (current) {
        next[index] = { ...current, content: `${current.content}${event.content ?? ""}` };
      }
      break;
    }
    case "assistant_message_completed": {
      const current = asAssistant(next[index]);
      if (current) {
        next[index] = { ...current, content: current.content || event.content || "", completedAt: event.createdAt };
      }
      break;
    }
    case "assistant_message_error": {
      const current = asAssistant(next[index]);
      if (index === -1 && messageId) {
        next.push({
          id: messageId,
          type: "assistant",
          content: event.content ?? "The run failed.",
          reasoning: "",
          createdAt: event.createdAt,
          completedAt: event.createdAt
        });
      } else if (current) {
        next[index] = {
          ...current,
          content: current.content || event.content || "The run failed.",
          completedAt: event.createdAt
        };
      }
      break;
    }
    default:
      break;
  }

  return next;
}

/** Fold a single event into a thread. Idempotent and deterministic. */
export function reduceChatEvent(thread: ChatThread, event: ChatEvent): ChatThread {
  // Seq is the per-session monotonic dedup key. Anything not strictly newer was already applied.
  if ((thread.lastSeq ?? 0) >= event.seq) {
    return thread;
  }

  let entries = thread.entries;
  let activities = thread.activities ?? [];
  let run = thread.run ?? null;

  if (isInlineEntryEvent(event.type)) {
    if (event.type === "conversation_compacted") {
      entries = reduceNoticeEntry(entries, event);
    } else if (APPROVAL_EVENT_TYPES.has(event.type)) {
      entries = reduceApprovalEntry(entries, event);
      // Approval events are inline cards but also move the run between awaiting_approval/completed.
      run = reduceRun(run, event);
    } else {
      entries = reduceToolEntry(entries, event);
    }
  } else if (isActivityEvent(event.type)) {
    activities = [...activities, buildActivity(event)];
    run = reduceRun(run, event);
  } else {
    entries = reduceMessageEntry(entries, event);
    if (event.type === "assistant_message_error" && run && run.status !== "completed") {
      run = {
        ...run,
        status: "failed",
        completedAt: event.createdAt,
        error: run.error ?? event.content ?? "The run failed."
      };
    }
  }

  return {
    ...thread,
    entries,
    activities,
    run,
    lastSeq: event.seq,
    updatedAt: event.createdAt
  };
}

/** Reset only the event-derived projection, preserving thread metadata. */
export function resetProjection(thread: ChatThread): ChatThread {
  return { ...thread, entries: [], activities: [], run: null, lastSeq: 0 };
}

/** Fold an ordered list of events. With `replace`, rebuild the projection from scratch. */
export function reduceChatEvents(
  thread: ChatThread,
  events: ChatEvent[],
  options?: { replace?: boolean }
): ChatThread {
  const start = options?.replace ? resetProjection(thread) : thread;
  return events.reduce(reduceChatEvent, start);
}

/**
 * A run that has shown no activity for this long is treated as dead even though it never produced a
 * terminal event. This is a client-side safety net for runs the backend failed to reconcile (e.g. a
 * hard crash with no restart yet): without it the composer would stay disabled indefinitely. The
 * window is generous so a legitimately long-running tool call is never cut off prematurely.
 */
export const STALE_RUN_MS = 15 * 60 * 1000;

/**
 * Whether the thread currently has an in-flight run; drives send/stop/loading affordances. `now` is
 * injectable for deterministic tests. A `running`/`queued` run whose last event is older than
 * {@link STALE_RUN_MS} is considered stale and not busy, so a stuck run eventually frees the composer
 * on the next render even without an explicit stop.
 */
export function isThreadBusy(thread: ChatThread, now: number = Date.now()): boolean {
  const status = thread.run?.status;
  if (status === "completed" || status === "failed") return false;
  // A run parked on an approval prompt is stopped, not busy: the composer (and the approve/decline
  // buttons) must stay interactive while the user decides.
  if (status === "awaiting_approval") return false;
  if (status === "running") {
    // A "running" run is always anchored to a server run_started event, so updatedAt is meaningful:
    // it tracks the last streamed event. If that is older than the window the run is wedged (orphaned
    // with no terminal event) and should stop blocking the composer.
    const lastActivity = Date.parse(thread.updatedAt);
    if (Number.isFinite(lastActivity) && now - lastActivity > STALE_RUN_MS) return false;
    return true;
  }
  // "queued" is the optimistic local state before the server's run_started lands (it never bumps
  // updatedAt, and a thread hydrated from the server never carries it), and "cancelling" is set the
  // instant the user hits stop. Both stay busy until a real terminal event resolves them.
  if (status === "queued" || status === "cancelling") return true;
  // Legacy fallback for threads without run events: an assistant bubble still streaming.
  return thread.entries.some((entry) => entry.type === "assistant" && !entry.completedAt);
}
