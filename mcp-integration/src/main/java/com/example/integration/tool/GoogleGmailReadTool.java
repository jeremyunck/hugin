package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GmailMessageFormatter;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.gmail.model.Message;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Reads a Gmail message and returns headers plus the best available plain-text body. */
@Component
public class GoogleGmailReadTool implements LocalTool {

    private final GoogleWorkspaceClientFactory google;

    public GoogleGmailReadTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public String name() {
        return "google_gmail_read";
    }

    @Override
    public String description() {
        return "Read a Gmail message by id and return its headers, labels, snippet, and body text. "
                + "Use this after searching Gmail to inspect a specific email in detail.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message_id", Map.of(
                                "type", "string",
                                "description", "The Gmail message id returned by google_gmail_search.")),
                "required", List.of("message_id"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String messageId = requiredString(arguments, "message_id");
            Message message = google.gmail().users().messages()
                    .get("me", messageId)
                    .setFormat("full")
                    .setMetadataHeaders(List.of("From", "To", "Cc", "Subject", "Date"))
                    .execute();
            return GmailMessageFormatter.formatMessage(message);
        });
    }
}
