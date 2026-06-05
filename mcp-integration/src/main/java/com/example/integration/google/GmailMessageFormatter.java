package com.example.integration.google;

import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartHeader;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Utility methods for formatting Gmail message payloads and headers for tool output. */
public final class GmailMessageFormatter {

    private GmailMessageFormatter() {
    }

    public static String formatMessage(Message message) {
        StringBuilder sb = new StringBuilder();
        sb.append("Message id: ").append(nullToEmpty(message.getId())).append('\n');
        if (message.getThreadId() != null && !message.getThreadId().isBlank()) {
            sb.append("threadId: ").append(message.getThreadId()).append('\n');
        }
        appendHeader(sb, message, "From");
        appendHeader(sb, message, "To");
        appendHeader(sb, message, "Cc");
        appendHeader(sb, message, "Date");
        appendHeader(sb, message, "Subject");
        appendHeader(sb, message, "Message-ID");
        appendHeader(sb, message, "Reply-To");
        appendHeader(sb, message, "References");
        if (message.getLabelIds() != null && !message.getLabelIds().isEmpty()) {
            sb.append("labels: ").append(String.join(", ", message.getLabelIds())).append('\n');
        }
        if (message.getSnippet() != null && !message.getSnippet().isBlank()) {
            sb.append("snippet: ").append(message.getSnippet()).append('\n');
        }

        String body = extractPreferredBody(message.getPayload());
        sb.append('\n').append("Body:").append('\n');
        sb.append(body.isBlank() ? "(no plain-text body found)" : body);
        return sb.toString();
    }

    public static String headerValue(Message message, String headerName) {
        if (message == null || message.getPayload() == null || message.getPayload().getHeaders() == null) {
            return "";
        }
        for (MessagePartHeader header : message.getPayload().getHeaders()) {
            if (headerName.equalsIgnoreCase(header.getName())) {
                return header.getValue() == null ? "" : header.getValue();
            }
        }
        return "";
    }

    public static String extractPreferredBody(MessagePart part) {
        String plain = findBody(part, true);
        if (!plain.isBlank()) {
            return plain;
        }
        return findBody(part, false);
    }

    private static String findBody(MessagePart part, boolean preferPlain) {
        if (part == null) {
            return "";
        }
        List<MessagePart> parts = part.getParts();
        if (parts != null && !parts.isEmpty()) {
            for (MessagePart child : parts) {
                String text = findBody(child, preferPlain);
                if (!text.isBlank()) {
                    return text;
                }
            }
            return "";
        }

        String data = part.getBody() == null ? null : part.getBody().getData();
        if (data == null || data.isBlank()) {
            return "";
        }

        String mimeType = part.getMimeType() == null ? "" : part.getMimeType().toLowerCase();
        String decoded = decodeBase64Url(data);
        if (preferPlain) {
            if (mimeType.contains("text/plain") || !mimeType.contains("text/html")) {
                return decoded.trim();
            }
            return "";
        }
        if (mimeType.contains("text/html")) {
            return stripHtml(decoded).trim();
        }
        return decoded.trim();
    }

    private static void appendHeader(StringBuilder sb, Message message, String name) {
        String value = headerValue(message, name);
        if (!value.isBlank()) {
            sb.append(name.toLowerCase()).append(": ").append(value).append('\n');
        }
    }

    private static String decodeBase64Url(String data) {
        String normalized = data.replace('-', '+').replace('_', '/');
        int remainder = normalized.length() % 4;
        if (remainder > 0) {
            normalized += "=".repeat(4 - remainder);
        }
        return new String(Base64.getDecoder().decode(normalized), StandardCharsets.UTF_8);
    }

    private static String stripHtml(String html) {
        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n\n")
                .replaceAll("(?i)<[^>]+>", "");
        return text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
