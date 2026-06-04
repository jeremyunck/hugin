package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import jakarta.mail.internet.MimeMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Sends a plain-text email via the configured SMTP mail sender. */
@Component
@ConditionalOnBean(JavaMailSender.class)
public class SendEmailTool implements LocalTool {

    private final JavaMailSender mailSender;

    public SendEmailTool(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public String name() {
        return "send_email";
    }

    @Override
    public String description() {
        return "Send a plain-text email. Requires SMTP mail configuration. Use this for outward "
                + "communication when a human needs a message outside the assistant's chat channel.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "to", Map.of("type", "string", "description", "Recipient email address."),
                        "subject", Map.of("type", "string", "description", "Subject line."),
                        "body", Map.of("type", "string", "description", "Plain-text email body."),
                        "cc", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Optional CC recipients."),
                        "bcc", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Optional BCC recipients.")),
                "required", List.of("to", "subject", "body"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        String to = requiredString(arguments, "to");
        String subject = requiredString(arguments, "subject");
        String body = requiredString(arguments, "body");

        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(body, false);

        addRecipients(helper::setCc, arguments.get("cc"));
        addRecipients(helper::setBcc, arguments.get("bcc"));

        mailSender.send(message);
        return "Sent email to " + to;
    }

    private static void addRecipients(RecipientSetter setter, Object value) throws Exception {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        String[] recipients = list.stream().map(Object::toString).filter(s -> !s.isBlank()).toArray(String[]::new);
        if (recipients.length > 0) {
            setter.accept(recipients);
        }
    }

    @FunctionalInterface
    private interface RecipientSetter {
        void accept(String... recipients) throws Exception;
    }
}
