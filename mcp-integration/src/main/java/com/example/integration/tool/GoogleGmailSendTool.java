package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GmailMessageComposer;
import com.example.integration.google.GmailMessageFormatter;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.gmail.model.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Sends a Gmail message or replies to an existing thread. */
@Component
public class GoogleGmailSendTool implements LocalTool {

    private final GoogleWorkspaceClientFactory google;

    public GoogleGmailSendTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public String name() {
        return "google_gmail_send";
    }

    @Override
    public String description() {
        return "Send a Gmail message with plain-text body. Provide 'to' and 'subject' for a new email, "
                + "or 'reply_to_message_id' to reply to an existing Gmail message and let Hugin infer the "
                + "thread, subject, and recipient from the original email.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "to", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Recipient email addresses. Optional when replying to an existing message."),
                        "subject", Map.of("type", "string", "description", "Subject line. Optional when replying."),
                        "body", Map.of("type", "string", "description", "Plain-text email body."),
                        "cc", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Optional CC recipients."),
                        "bcc", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Optional BCC recipients."),
                        "reply_to_message_id", Map.of(
                                "type", "string",
                                "description", "Optional Gmail message id to reply to. Hugin uses the original message to infer the reply thread and recipient."),
                        "thread_id", Map.of(
                                "type", "string",
                                "description", "Optional Gmail thread id. Usually inferred automatically when reply_to_message_id is provided.")),
                "required", List.of("body"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String body = requiredString(arguments, "body");
            List<String> to = toStringList(arguments.get("to"));
            String subject = optionalString(arguments, "subject", "");
            List<String> cc = toStringList(arguments.get("cc"));
            List<String> bcc = toStringList(arguments.get("bcc"));
            String replyToMessageId = optionalString(arguments, "reply_to_message_id", "");
            String threadId = optionalString(arguments, "thread_id", "");

            Message replyToOriginal = null;
            if (!replyToMessageId.isBlank()) {
                replyToOriginal = google.gmail().users().messages()
                        .get("me", replyToMessageId)
                        .setFormat("metadata")
                        .setMetadataHeaders(List.of("From", "Reply-To", "Subject", "Message-ID"))
                        .execute();
            }

            Message toSend = GmailMessageComposer.composePlainTextMessage(
                    to,
                    subject,
                    body,
                    cc,
                    bcc,
                    replyToOriginal,
                    threadId);

            Message sent = google.gmail().users().messages().send("me", toSend).execute();
            StringBuilder sb = new StringBuilder();
            sb.append("Sent Gmail message.\n");
            sb.append("messageId: ").append(sent.getId()).append('\n');
            if (sent.getThreadId() != null && !sent.getThreadId().isBlank()) {
                sb.append("threadId: ").append(sent.getThreadId()).append('\n');
            }
            if (!to.isEmpty()) {
                sb.append("to: ").append(String.join(", ", to)).append('\n');
            } else if (replyToOriginal != null) {
                String inferred = GmailMessageFormatter.headerValue(replyToOriginal, "Reply-To");
                if (inferred.isBlank()) {
                    inferred = GmailMessageFormatter.headerValue(replyToOriginal, "From");
                }
                if (!inferred.isBlank()) {
                    sb.append("to: ").append(inferred).append('\n');
                }
            }
            String effectiveSubject = subject == null ? "" : subject.trim();
            if (effectiveSubject.isBlank() && replyToOriginal != null) {
                effectiveSubject = replySubject(GmailMessageFormatter.headerValue(replyToOriginal, "Subject"));
            }
            if (!effectiveSubject.isBlank()) {
                sb.append("subject: ").append(effectiveSubject).append('\n');
            }
            return sb.toString().trim();
        });
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

    private static String replySubject(String originalSubject) {
        String trimmed = originalSubject == null ? "" : originalSubject.trim();
        if (trimmed.isBlank()) {
            return "Re:";
        }
        if (trimmed.regionMatches(true, 0, "Re:", 0, 3)
                || trimmed.regionMatches(true, 0, "Fwd:", 0, 4)) {
            return trimmed;
        }
        return "Re: " + trimmed;
    }
}
