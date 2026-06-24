package com.example.agent.tool;

import java.util.List;
import java.util.Map;

/**
 * Raised by a tool whose effect is too consequential to perform without explicit user verification.
 *
 * <p>Instead of carrying out the side effect, the tool throws this with a structured summary of what
 * it <em>would</em> do. The chat layer ({@code ChatSessionService}) catches it, persists an
 * {@code approval_required} event describing the pending action, and stops the run so the UI can
 * prompt the user to approve or decline. The action itself is only carried out later, by the approval
 * endpoint — never by the tool. This keeps the model from silently destroying data: a human is always
 * in the loop.
 *
 * <p>{@link #items()} is a list of small, JSON-serializable maps the front-end renders as the
 * approval prompt (e.g. one entry per email with its sender and subject).
 */
public class ToolApprovalRequiredException extends RuntimeException {

    private final String kind;
    private final List<Map<String, Object>> items;
    private String toolName;
    private String toolCallId;

    public ToolApprovalRequiredException(String kind, String summary, List<Map<String, Object>> items) {
        super(summary);
        this.kind = kind;
        this.items = items == null ? List.of() : List.copyOf(items);
    }

    /** A short machine-readable category for the pending action, e.g. {@code "email_delete"}. */
    public String kind() {
        return kind;
    }

    /** Human-readable one-line summary of the action awaiting approval. */
    public String summary() {
        return getMessage();
    }

    /** The per-item details the UI lists in the approval prompt (never null). */
    public List<Map<String, Object>> items() {
        return items;
    }

    public String toolName() {
        return toolName;
    }

    public String toolCallId() {
        return toolCallId;
    }

    /**
     * Records which tool call raised this so the chat layer can close the matching tool card. Set by
     * the agent loop, which knows the call id; the tool itself does not.
     */
    public void attachToolCall(String toolCallId, String toolName) {
        this.toolCallId = toolCallId;
        this.toolName = toolName;
    }
}
