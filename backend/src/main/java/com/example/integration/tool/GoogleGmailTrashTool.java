package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.agent.tool.ToolApprovalRequiredException;
import com.example.integration.google.GmailMessageFormatter;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.gmail.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Moves one or more Gmail messages to Trash — but only after the user explicitly approves.
 *
 * <p>This is a recoverable move to the Trash label, <em>not</em> a permanent delete: the messages can
 * still be restored from Trash (and Gmail purges Trash on its own after ~30 days). Even so, it never
 * acts on its own. When the model calls it, the tool looks up each message's sender and subject, then
 * raises a {@link ToolApprovalRequiredException} carrying that summary. The chat layer turns that into
 * an approval prompt in the UI; the actual move to Trash happens later, from the approval endpoint,
 * only if the user clicks Approve.
 */
@Component
public class GoogleGmailTrashTool implements LocalTool {

    private static final Logger log = LoggerFactory.getLogger(GoogleGmailTrashTool.class);

    private final GoogleWorkspaceClientFactory google;

    public GoogleGmailTrashTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public boolean isAvailable() {
        return google.available();
    }

    @Override
    public String name() {
        return "google_gmail_trash";
    }

    @Override
    public String description() {
        return "Move one or more Gmail messages to Trash. This does NOT permanently delete the emails: "
                + "it moves them to the Trash label, where they can be restored until Gmail empties the "
                + "Trash (about 30 days later). It also ALWAYS requires explicit user approval and never "
                + "acts immediately. Provide the Gmail message ids (from google_gmail_search) to trash; "
                + "Bouw shows the user a summary of each email and asks them to approve or decline before "
                + "anything is moved.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message_ids", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Gmail message ids to move to Trash. Get these from "
                                        + "google_gmail_search.")),
                "required", List.of("message_ids"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        // Not run inside google.guarded(...): that helper turns every throwable into a string, which
        // would swallow the approval signal. Configuration is checked explicitly, and per-message
        // metadata failures degrade to a placeholder rather than aborting the prompt.
        if (!google.isConfigured()) {
            return google.unavailableMessage();
        }
        List<String> ids = toStringList(arguments.get("message_ids"));
        if (ids.isEmpty()) {
            return "Provide at least one Gmail message id in 'message_ids' (use google_gmail_search to find them).";
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            try {
                Message message = google.gmail().users().messages()
                        .get("me", id)
                        .setFormat("metadata")
                        .setMetadataHeaders(List.of("From", "Subject"))
                        .execute();
                item.put("from", GmailMessageFormatter.headerValue(message, "From"));
                item.put("subject", GmailMessageFormatter.headerValue(message, "Subject"));
                item.put("snippet", message.getSnippet() == null ? "" : message.getSnippet());
            } catch (Exception e) {
                log.warn("Could not load Gmail metadata for {} before trash approval: {}", id, e.getMessage());
                item.put("from", "");
                item.put("subject", "(could not load message details)");
                item.put("snippet", "");
            }
            items.add(item);
        }

        String summary = ids.size() == 1
                ? "Approval required to move 1 email to Trash."
                : "Approval required to move " + ids.size() + " emails to Trash.";
        throw new ToolApprovalRequiredException("email_trash", summary, items);
    }

    private static List<String> toStringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String text = item.toString().trim();
            if (!text.isBlank()) {
                result.add(text);
            }
        }
        return result;
    }
}
