package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GmailMessageFormatter;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonErrorContainer;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Searches Gmail using Gmail query syntax and returns recent matching messages. */
@Component
public class GoogleGmailSearchTool implements LocalTool {

    private static final Logger log = LoggerFactory.getLogger(GoogleGmailSearchTool.class);

    private final GoogleWorkspaceClientFactory google;

    public GoogleGmailSearchTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public String name() {
        return "google_gmail_search";
    }

    @Override
    public String description() {
        return "Search the authenticated Gmail mailbox using Gmail query syntax and return matching "
                + "messages with metadata, labels, and snippets. Defaults to inbox results when no query "
                + "is provided. Use this to triage email or find candidates for a deeper read.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "Optional Gmail search query. Defaults to 'in:inbox'."),
                        "max_results", Map.of(
                                "type", "integer",
                                "minimum", 1,
                                "maximum", 100,
                                "description", "Maximum number of messages to return. Defaults to 10."),
                        "include_spam_trash", Map.of(
                                "type", "boolean",
                                "description", "Include spam and trash in the search results."),
                        "label_ids", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Optional Gmail label ids to require on every result."),
                        "page_token", Map.of(
                                "type", "string",
                                "description", "Optional page token from a previous search response.")),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String query = optionalString(arguments, "query", "in:inbox");
            int maxResults = optionalInt(arguments, "max_results", 10);
            boolean includeSpamTrash = optionalBoolean(arguments, "include_spam_trash", false);
            String pageToken = optionalString(arguments, "page_token", "");
            List<String> labelIds = toStringList(arguments.get("label_ids"));

            com.google.api.services.gmail.Gmail.Users.Messages.List request = google.gmail().users()
                    .messages()
                    .list("me")
                    .setQ(query)
                    .setMaxResults((long) maxResults)
                    .setIncludeSpamTrash(includeSpamTrash);
            if (!pageToken.isBlank()) {
                request.setPageToken(pageToken);
            }
            if (!labelIds.isEmpty()) {
                request.setLabelIds(labelIds);
            }

            ListMessagesResponse response = request.execute();
            List<Message> messages = fetchMetadataForResults(response);

            if (messages.isEmpty()) {
                return "(no messages found for query: " + query + ")";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Query: ").append(query).append('\n');
            sb.append("resultSizeEstimate: ").append(response.getResultSizeEstimate()).append('\n');
            if (response.getNextPageToken() != null && !response.getNextPageToken().isBlank()) {
                sb.append("nextPageToken: ").append(response.getNextPageToken()).append('\n');
            }
            sb.append('\n');

            for (int i = 0; i < messages.size(); i++) {
                Message message = messages.get(i);
                sb.append(i + 1).append(". id: ").append(message.getId()).append('\n');
                if (message.getThreadId() != null && !message.getThreadId().isBlank()) {
                    sb.append("   threadId: ").append(message.getThreadId()).append('\n');
                }
                String from = GmailMessageFormatter.headerValue(message, "From");
                if (!from.isBlank()) {
                    sb.append("   from: ").append(from).append('\n');
                }
                String subject = GmailMessageFormatter.headerValue(message, "Subject");
                if (!subject.isBlank()) {
                    sb.append("   subject: ").append(subject).append('\n');
                }
                String date = GmailMessageFormatter.headerValue(message, "Date");
                if (!date.isBlank()) {
                    sb.append("   date: ").append(date).append('\n');
                }
                if (message.getLabelIds() != null && !message.getLabelIds().isEmpty()) {
                    sb.append("   labels: ").append(String.join(", ", message.getLabelIds())).append('\n');
                }
                if (message.getSnippet() != null && !message.getSnippet().isBlank()) {
                    sb.append("   snippet: ").append(message.getSnippet()).append('\n');
                }
            }
            return sb.toString().trim();
        });
    }

    private List<Message> fetchMetadataForResults(ListMessagesResponse response) throws Exception {
        List<Message> messages = new ArrayList<>();
        if (response.getMessages() == null || response.getMessages().isEmpty()) {
            return messages;
        }
        Map<String, Message> fetchedById = fetchMetadataBatch(response.getMessages());
        for (Message message : response.getMessages()) {
            Message full = fetchedById.get(message.getId());
            messages.add(full != null ? full : message);
        }
        return messages;
    }

    private Map<String, Message> fetchMetadataBatch(List<Message> messageRefs) throws Exception {
        Map<String, Message> fetchedById = new LinkedHashMap<>();
        if (messageRefs == null || messageRefs.isEmpty()) {
            return fetchedById;
        }

        BatchRequest batch = google.gmail().batch();
        for (Message messageRef : messageRefs) {
            if (messageRef == null || messageRef.getId() == null || messageRef.getId().isBlank()) {
                continue;
            }
            google.gmail().users().messages()
                    .get("me", messageRef.getId())
                    .setFormat("metadata")
                    .setMetadataHeaders(List.of("From", "Subject", "Date", "To", "Cc"))
                    .queue(batch, GoogleJsonErrorContainer.class, new JsonBatchCallback<>() {
                        @Override
                        public void onSuccess(Message message, com.google.api.client.http.HttpHeaders headers) {
                            fetchedById.put(message.getId(), message);
                        }

                        @Override
                        public void onFailure(GoogleJsonError error, com.google.api.client.http.HttpHeaders headers) {
                            logBatchFailure(messageRef.getId(), error);
                        }
                    });
        }
        batch.execute();
        return fetchedById;
    }

    private void logBatchFailure(String messageId, GoogleJsonError error) {
        String reason = error == null ? "unknown error" : error.getMessage();
        log.warn("Failed to fetch Gmail metadata for {}: {}", messageId, reason);
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
