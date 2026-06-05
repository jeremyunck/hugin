package com.example.integration.google;

import com.google.api.services.gmail.model.Message;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Properties;

/** Builds Gmail API send requests from plain-text content and optional reply context. */
public final class GmailMessageComposer {

    private GmailMessageComposer() {
    }

    public static Message composePlainTextMessage(
            List<String> to,
            String subject,
            String body,
            List<String> cc,
            List<String> bcc,
            Message replyToOriginal,
            String threadId) throws Exception {

        if ((to == null || to.isEmpty()) && replyToOriginal == null) {
            throw new IllegalArgumentException("At least one recipient is required");
        }

        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()));

        String normalizedSubject = normalizeSubject(subject, replyToOriginal);
        mime.setSubject(normalizedSubject, StandardCharsets.UTF_8.name());
        mime.setText(body == null ? "" : body, StandardCharsets.UTF_8.name());

        if (replyToOriginal != null) {
            String replyTarget = firstNonBlank(
                    GmailMessageFormatter.headerValue(replyToOriginal, "Reply-To"),
                    GmailMessageFormatter.headerValue(replyToOriginal, "From"));
            if ((to == null || to.isEmpty()) && !replyTarget.isBlank()) {
                to = List.of(replyTarget);
            }
            String originalMessageId = GmailMessageFormatter.headerValue(replyToOriginal, "Message-ID");
            if (!originalMessageId.isBlank()) {
                mime.setHeader("In-Reply-To", originalMessageId);
                mime.setHeader("References", originalMessageId);
            }
            if (threadId == null || threadId.isBlank()) {
                threadId = replyToOriginal.getThreadId();
            }
        }

        setRecipients(mime, RecipientType.TO, to);
        setRecipients(mime, RecipientType.CC, cc);
        setRecipients(mime, RecipientType.BCC, bcc);

        if (mime.getAllRecipients() == null || mime.getAllRecipients().length == 0) {
            throw new IllegalArgumentException("At least one recipient is required");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        mime.writeTo(out);

        Message message = new Message()
                .setRaw(Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray()));
        if (threadId != null && !threadId.isBlank()) {
            message.setThreadId(threadId);
        }
        return message;
    }

    private static void setRecipients(MimeMessage mime, RecipientType type, List<String> recipients) throws Exception {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        for (String recipient : recipients) {
            String normalized = normalizeAddress(recipient);
            if (!normalized.isBlank()) {
                mime.addRecipient(type, new InternetAddress(normalized, true));
            }
        }
    }

    private static String normalizeAddress(String value) throws Exception {
        if (value == null) {
            return "";
        }
        InternetAddress parsed = new InternetAddress(value, true);
        parsed.validate();
        return parsed.getAddress() == null ? "" : parsed.toUnicodeString();
    }

    private static String normalizeSubject(String subject, Message replyToOriginal) {
        String trimmed = subject == null ? "" : subject.trim();
        if (!trimmed.isBlank()) {
            return trimmed;
        }
        if (replyToOriginal == null) {
            return "";
        }
        String originalSubject = GmailMessageFormatter.headerValue(replyToOriginal, "Subject").trim();
        if (originalSubject.isBlank()) {
            return "Re:";
        }
        if (originalSubject.regionMatches(true, 0, "Re:", 0, 3)
                || originalSubject.regionMatches(true, 0, "Fwd:", 0, 4)) {
            return originalSubject;
        }
        return "Re: " + originalSubject;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }
}
