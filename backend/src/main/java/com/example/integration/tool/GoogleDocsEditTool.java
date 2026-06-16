package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GoogleIds;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.docs.v1.model.BatchUpdateDocumentRequest;
import com.google.api.services.docs.v1.model.EndOfSegmentLocation;
import com.google.api.services.docs.v1.model.InsertTextRequest;
import com.google.api.services.docs.v1.model.Location;
import com.google.api.services.docs.v1.model.ReplaceAllTextRequest;
import com.google.api.services.docs.v1.model.Request;
import com.google.api.services.docs.v1.model.SubstringMatchCriteria;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Edits an existing Google Doc via the Docs batchUpdate API. Supports three operations:
 * <ul>
 *   <li>{@code append} — add {@code text} to the end of the document.</li>
 *   <li>{@code insert} — insert {@code text} at a 1-based character {@code index}.</li>
 *   <li>{@code replace} — replace every occurrence of {@code find} with {@code text}.</li>
 * </ul>
 */
@Component
public class GoogleDocsEditTool implements LocalTool {

    private final GoogleWorkspaceClientFactory google;

    public GoogleDocsEditTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public boolean isAvailable() {
        return google.available();
    }

    @Override
    public String name() {
        return "google_docs_edit";
    }

    @Override
    public String description() {
        return "Edit an existing Google Doc. operation='append' adds text at the end; "
                + "operation='insert' inserts text at a 1-based character index; "
                + "operation='replace' replaces all occurrences of 'find' with 'text'. "
                + "Accepts a document id or a full Google Docs URL.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "document_id", Map.of(
                                "type", "string",
                                "description", "The Google Doc id, or a full docs.google.com URL."),
                        "operation", Map.of(
                                "type", "string",
                                "enum", List.of("append", "insert", "replace"),
                                "description", "The edit to perform."),
                        "text", Map.of(
                                "type", "string",
                                "description", "Required for operation 'append' and 'insert'; for 'replace' it is "
                                        + "the replacement and may be omitted to delete the matched text."),
                        "find", Map.of(
                                "type", "string",
                                "description", "For operation='replace': the substring to search for."),
                        "index", Map.of(
                                "type", "integer",
                                "description", "For operation='insert': 1-based character index to insert at."),
                        "match_case", Map.of(
                                "type", "boolean",
                                "description", "For operation='replace': case-sensitive match (default true).")),
                "required", List.of("document_id", "operation"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String documentId = GoogleIds.extract(requiredString(arguments, "document_id"));
            String operation = requiredString(arguments, "operation").trim().toLowerCase();

            Request request;
            String summary;
            switch (operation) {
                case "append" -> {
                    String text = requiredString(arguments, "text");
                    request = new Request().setInsertText(new InsertTextRequest()
                            .setText(text)
                            .setEndOfSegmentLocation(new EndOfSegmentLocation()));
                    summary = "Appended " + text.length() + " characters.";
                }
                case "insert" -> {
                    String text = requiredString(arguments, "text");
                    int index = optionalInt(arguments, "index", 1);
                    request = new Request().setInsertText(new InsertTextRequest()
                            .setText(text)
                            .setLocation(new Location().setIndex(index)));
                    summary = "Inserted " + text.length() + " characters at index " + index + ".";
                }
                case "replace" -> {
                    String find = requiredString(arguments, "find");
                    String text = optionalString(arguments, "text", "");
                    boolean matchCase = optionalBoolean(arguments, "match_case", true);
                    request = new Request().setReplaceAllText(new ReplaceAllTextRequest()
                            .setContainsText(new SubstringMatchCriteria().setText(find).setMatchCase(matchCase))
                            .setReplaceText(text));
                    summary = "Replaced occurrences of '" + find + "'.";
                }
                default -> {
                    return "Error: unknown operation '" + operation + "'. Use append, insert, or replace.";
                }
            }

            google.docs().documents().batchUpdate(documentId,
                    new BatchUpdateDocumentRequest().setRequests(List.of(request))).execute();

            return summary + "\nurl: " + GoogleIds.docUrl(documentId);
        });
    }
}
