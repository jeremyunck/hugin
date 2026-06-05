package com.example.integration.tool;

import com.example.integration.google.GoogleErrors;
import com.example.integration.google.GmailMessageFormatter;
import com.example.integration.google.GmailMessageComposer;
import com.example.integration.google.GoogleSheetValues;
import com.example.integration.google.GoogleIds;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.example.integration.google.GoogleWorkspaceProperties;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.MessagePart;
import com.google.api.services.gmail.model.MessagePartBody;
import com.google.api.services.gmail.model.MessagePartHeader;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the Google Workspace tools that don't require live Google calls: the graceful
 * "unavailable" path when no credentials are configured, schema/name correctness, and the helper
 * utilities (id extraction, value coercion).
 */
class GoogleWorkspaceToolsTest {

    /** A factory with no credentials configured -> isConfigured() is false. */
    private GoogleWorkspaceClientFactory unconfiguredFactory() {
        return new GoogleWorkspaceClientFactory(
                new GoogleWorkspaceProperties("", "", "", 8765, "Hugin", "", ""));
    }

    @Test
    void factoryReportsUnavailableWithoutCredentials() {
        assertThat(unconfiguredFactory().isConfigured()).isFalse();
    }

    @Test
    void allToolsReturnUnavailableMessageWithoutCredentials() throws Exception {
        GoogleWorkspaceClientFactory f = unconfiguredFactory();
        List<? extends com.example.agent.tool.LocalTool> tools = List.of(
                new GoogleDocsCreateTool(f),
                new GoogleDocsReadTool(f),
                new GoogleDocsEditTool(f),
                new GoogleSheetsCreateTool(f),
                new GoogleSheetsReadTool(f),
                new GoogleSheetsWriteTool(f),
                new GoogleSheetsAppendTool(f),
                new GoogleGmailSearchTool(f),
                new GoogleGmailReadTool(f),
                new GoogleGmailSendTool(f));

        for (var tool : tools) {
            // document_id/spreadsheet_id are required by some tools but the unavailable check runs first.
            String result = tool.execute(Map.of(
                    "document_id", "x", "spreadsheet_id", "x",
                    "operation", "append", "text", "hi",
                    "range", "Sheet1!A1", "values", List.of(List.of("a"))));
            assertThat(result).as(tool.name()).contains("unavailable");
        }
    }

    @Test
    void toolNamesAndSchemasAreCorrect() {
        GoogleWorkspaceClientFactory f = unconfiguredFactory();
        assertThat(new GoogleDocsCreateTool(f).name()).isEqualTo("google_docs_create");
        assertThat(new GoogleDocsReadTool(f).name()).isEqualTo("google_docs_read");
        assertThat(new GoogleDocsEditTool(f).name()).isEqualTo("google_docs_edit");
        assertThat(new GoogleSheetsCreateTool(f).name()).isEqualTo("google_sheets_create");
        assertThat(new GoogleSheetsReadTool(f).name()).isEqualTo("google_sheets_read");
        assertThat(new GoogleSheetsWriteTool(f).name()).isEqualTo("google_sheets_write");
        assertThat(new GoogleSheetsAppendTool(f).name()).isEqualTo("google_sheets_append");
        assertThat(new GoogleGmailSearchTool(f).name()).isEqualTo("google_gmail_search");
        assertThat(new GoogleGmailReadTool(f).name()).isEqualTo("google_gmail_read");
        assertThat(new GoogleGmailSendTool(f).name()).isEqualTo("google_gmail_send");

        @SuppressWarnings("unchecked")
        var props = (Map<String, ?>) new GoogleSheetsReadTool(f).inputSchema().get("properties");
        assertThat(props).containsKeys("spreadsheet_id", "range");
    }

    @Test
    void gmailToolSchemasExposeExpectedFields() {
        GoogleWorkspaceClientFactory f = unconfiguredFactory();
        @SuppressWarnings("unchecked")
        var searchProps = (Map<String, ?>) new GoogleGmailSearchTool(f).inputSchema().get("properties");
        assertThat(searchProps).containsKeys("query", "max_results", "include_spam_trash", "label_ids", "page_token");

        @SuppressWarnings("unchecked")
        var readProps = (Map<String, ?>) new GoogleGmailReadTool(f).inputSchema().get("properties");
        assertThat(readProps).containsKey("message_id");

        @SuppressWarnings("unchecked")
        var sendProps = (Map<String, ?>) new GoogleGmailSendTool(f).inputSchema().get("properties");
        assertThat(sendProps).containsKeys("to", "subject", "body", "cc", "bcc", "reply_to_message_id", "thread_id");
    }

    @Test
    void extractsIdFromUrlOrBareId() {
        assertThat(GoogleIds.extract("https://docs.google.com/document/d/ABC123_xyz/edit"))
                .isEqualTo("ABC123_xyz");
        assertThat(GoogleIds.extract("https://docs.google.com/spreadsheets/d/SHEET-99/edit#gid=0"))
                .isEqualTo("SHEET-99");
        assertThat(GoogleIds.extract("PlainId42")).isEqualTo("PlainId42");
    }

    @Test
    void coercesNestedAndFlatValues() {
        assertThat(GoogleSheetValues.toRows(List.of(List.of("a", "b"), List.of("c", "d"))))
                .hasSize(2)
                .containsExactly(List.of("a", "b"), List.of("c", "d"));
        // A flat list is treated as a single row.
        assertThat(GoogleSheetValues.toRows(List.of("a", "b", "c")))
                .hasSize(1)
                .containsExactly(List.of("a", "b", "c"));
    }

    @Test
    void rejectsNonListValues() {
        assertThatThrownBy(() -> GoogleSheetValues.toRows("not a list"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2-D array");
    }

    @Test
    void guardedShortCircuitsWhenUnconfigured() {
        AtomicBoolean ran = new AtomicBoolean(false);
        String msg = unconfiguredFactory().guarded(() -> {
            ran.set(true);
            return "should not run";
        });
        assertThat(msg).contains("unavailable");
        assertThat(ran).isFalse();
    }

    @Test
    void guardedMapsFailuresToFriendlyMessages(@TempDir Path tmp) throws Exception {
        // A present (if dummy) credentials file makes isConfigured() true so guarded runs the call.
        Path creds = Files.writeString(tmp.resolve("creds.json"), "{}");
        GoogleWorkspaceClientFactory f = new GoogleWorkspaceClientFactory(
                new GoogleWorkspaceProperties(creds.toString(), "", "", 8765, "Hugin", "", ""));
        assertThat(f.isConfigured()).isTrue();

        assertThat(f.guarded(() -> {
            throw new FileNotFoundException("missing.json");
        })).contains("credentials file not found").contains("missing.json");

        assertThat(f.guarded(() -> {
            throw new IllegalArgumentException("bad values");
        })).contains("Google Workspace request failed").contains("bad values");
    }

    @Test
    void errorsDescribeFallsBackForGenericException() {
        assertThat(GoogleErrors.describe(new RuntimeException("boom")))
                .contains("Google Workspace request failed").contains("boom");
    }

    @Test
    void errorsDescribeAddsSharingHintFor403And404() {
        assertThat(GoogleErrors.describe(googleError(403, "The caller does not have permission")))
                .contains("403").contains("authenticated Google account");
        assertThat(GoogleErrors.describe(googleError(404, "Requested entity was not found")))
                .contains("404").contains("authenticated Google account");
    }

    @Test
    void errorsDescribeReportsOtherGoogleStatusCodesPlainly() {
        assertThat(GoogleErrors.describe(googleError(500, "Backend error")))
                .contains("Google API error 500").contains("Backend error")
                .doesNotContain("shared with the service account");
    }

    private GoogleJsonResponseException googleError(int code, String message) {
        GoogleJsonError details = new GoogleJsonError();
        details.setMessage(message);
        return new GoogleJsonResponseException(
                new HttpResponseException.Builder(code, message, new HttpHeaders()), details);
    }

    private static String encodeBase64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static MimeMessage decode(com.google.api.services.gmail.model.Message message) throws Exception {
        byte[] raw = Base64.getUrlDecoder().decode(message.getRaw());
        return new MimeMessage(Session.getInstance(new java.util.Properties()), new ByteArrayInputStream(raw));
    }

    private static List<String> addresses(MimeMessage mime, jakarta.mail.Message.RecipientType type) throws Exception {
        Address[] recipients = mime.getRecipients(type);
        if (recipients == null) {
            return List.of();
        }
        return java.util.Arrays.stream(recipients)
                .map(Address::toString)
                .map(String::trim)
                .toList();
    }

    private static String subject(MimeMessage mime) throws Exception {
        return mime.getSubject();
    }

    @Test
    void shareFileIsANoopForBlankEmail() {
        GoogleWorkspaceClientFactory f = unconfiguredFactory();
        assertThat(f.shareFile("file1", "", "writer")).isNull();
        assertThat(f.shareFile("file1", null, "writer")).isNull();
    }

    @Test
    void gmailMessageFormatterPrefersPlainTextBody() {
        MessagePart plainPart = new MessagePart()
                .setMimeType("text/plain")
                .setBody(new MessagePartBody().setData(encodeBase64Url("Hello from plain text")));
        MessagePart htmlPart = new MessagePart()
                .setMimeType("text/html")
                .setBody(new MessagePartBody().setData(encodeBase64Url("<p>Hello from <b>HTML</b></p>")));
        Message message = new Message()
                .setId("msg-1")
                .setThreadId("thread-1")
                .setSnippet("Snippet")
                .setLabelIds(List.of("INBOX", "UNREAD"))
                .setPayload(new MessagePart()
                        .setHeaders(List.of(
                                new MessagePartHeader().setName("From").setValue("Alice <alice@example.com>"),
                                new MessagePartHeader().setName("Subject").setValue("Test subject"),
                                new MessagePartHeader().setName("Date").setValue("Mon, 1 Jan 2026 12:00:00 -0600")))
                        .setParts(List.of(plainPart, htmlPart)));

        String formatted = GmailMessageFormatter.formatMessage(message);
        assertThat(formatted).contains("Message id: msg-1");
        assertThat(formatted).contains("from: Alice <alice@example.com>");
        assertThat(formatted).contains("subject: Test subject");
        assertThat(formatted).contains("Hello from plain text");
    }

    @Test
    void gmailMessageComposerBuildsNewMessage() throws Exception {
        com.google.api.services.gmail.model.Message message = GmailMessageComposer.composePlainTextMessage(
                List.of("alice@example.com"),
                "Hello",
                "Body text",
                List.of("cc@example.com"),
                List.of("bcc@example.com"),
                null,
                null);

        MimeMessage mime = decode(message);
        assertThat(subject(mime)).isEqualTo("Hello");
        assertThat(addresses(mime, jakarta.mail.Message.RecipientType.TO)).containsExactly("alice@example.com");
        assertThat(addresses(mime, jakarta.mail.Message.RecipientType.CC)).containsExactly("cc@example.com");
        assertThat(addresses(mime, jakarta.mail.Message.RecipientType.BCC)).containsExactly("bcc@example.com");
    }

    @Test
    void gmailMessageComposerBuildsReplyMessage() throws Exception {
        Message original = new Message()
                .setThreadId("thread-123")
                .setPayload(new MessagePart()
                        .setHeaders(List.of(
                                new MessagePartHeader().setName("From").setValue("Bob <bob@example.com>"),
                                new MessagePartHeader().setName("Subject").setValue("Status update"),
                                new MessagePartHeader().setName("Message-ID").setValue("<orig@example.com>"))));

        com.google.api.services.gmail.model.Message message = GmailMessageComposer.composePlainTextMessage(
                List.of(),
                "",
                "Reply text",
                List.of(),
                List.of(),
                original,
                null);

        MimeMessage mime = decode(message);
        assertThat(message.getThreadId()).isEqualTo("thread-123");
        assertThat(subject(mime)).isEqualTo("Re: Status update");
        assertThat(addresses(mime, jakarta.mail.Message.RecipientType.TO)).containsExactly("Bob <bob@example.com>");
        assertThat(mime.getHeader("In-Reply-To", null)).isEqualTo("<orig@example.com>");
        assertThat(mime.getHeader("References", null)).isEqualTo("<orig@example.com>");
    }

    /**
     * Regression guard for google_docs_edit argument handling: 'text' is optional for the 'replace'
     * operation (an empty/omitted text means "delete the matched text"), but required for 'append'.
     * Each operation fetches 'text' inside its own switch branch, so a replace without text proceeds
     * past argument parsing (failing only later at the dummy credentials/API step) rather than being
     * rejected for a missing argument.
     */
    @Test
    void docsEditTreatsTextAsOptionalForReplaceButRequiredForAppend(@TempDir Path tmp) throws Exception {
        Path creds = Files.writeString(tmp.resolve("creds.json"), "{}");
        GoogleDocsEditTool tool = new GoogleDocsEditTool(new GoogleWorkspaceClientFactory(
                new GoogleWorkspaceProperties(creds.toString(), "", "", 8765, "Hugin", "", "")));

        String replace = tool.execute(Map.of(
                "document_id", "doc1", "operation", "replace", "find", "TODO"));
        assertThat(replace).doesNotContain("Missing required argument");

        String append = tool.execute(Map.of("document_id", "doc1", "operation", "append"));
        assertThat(append).contains("Missing required argument").contains("text");
    }
}
