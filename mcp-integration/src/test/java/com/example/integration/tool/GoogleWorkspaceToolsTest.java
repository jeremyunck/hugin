package com.example.integration.tool;

import com.example.integration.google.GoogleErrors;
import com.example.integration.google.GoogleSheetValues;
import com.example.integration.google.GoogleIds;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.example.integration.google.GoogleWorkspaceProperties;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                new GoogleSheetsAppendTool(f));

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

        @SuppressWarnings("unchecked")
        var props = (Map<String, ?>) new GoogleSheetsReadTool(f).inputSchema().get("properties");
        assertThat(props).containsKeys("spreadsheet_id", "range");
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

    @Test
    void shareFileIsANoopForBlankEmail() {
        GoogleWorkspaceClientFactory f = unconfiguredFactory();
        assertThat(f.shareFile("file1", "", "writer")).isNull();
        assertThat(f.shareFile("file1", null, "writer")).isNull();
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
