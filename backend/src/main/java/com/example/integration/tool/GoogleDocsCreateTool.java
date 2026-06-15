package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GoogleIds;
import com.example.integration.google.GoogleDocsMarkdownRenderer;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.docs.v1.model.BatchUpdateDocumentRequest;
import com.google.api.services.docs.v1.model.Document;
import com.google.api.services.docs.v1.model.InsertTextRequest;
import com.google.api.services.docs.v1.model.Location;
import com.google.api.services.docs.v1.model.Request;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creates a new Google Doc, optionally seeded with body text and shared with a user. */
@Component
public class GoogleDocsCreateTool implements LocalTool {

    private static final GoogleDocsMarkdownRenderer MARKDOWN_RENDERER = new GoogleDocsMarkdownRenderer();

    private final GoogleWorkspaceClientFactory google;

    public GoogleDocsCreateTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public boolean isAvailable() {
        return google.available();
    }

    @Override
    public String name() {
        return "google_docs_create";
    }

    @Override
    public String description() {
        return "Create a new Google Doc. Optionally provide a title and Markdown body text; the "
                + "Markdown is rendered into headings, bullets, code blocks, and tables instead of "
                + "being inserted literally. Returns the new document's id and shareable URL. Note: "
                + "the doc is owned by the authenticated Google account; pass 'share_with' (an email) "
                + "so a person can open it.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of(
                                "type", "string",
                                "description", "Title of the new document. Defaults to 'Untitled document'."),
                        "text", Map.of(
                                "type", "string",
                                "description", "Optional Markdown body text rendered into the document."),
                        "share_with", Map.of(
                                "type", "string",
                                "description", "Optional email address to share the new doc with (as editor).")),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String title = optionalString(arguments, "title", "Untitled document");
            String text = optionalString(arguments, "text", "");

            Document created = google.docs().documents()
                    .create(new Document().setTitle(title))
                    .execute();
            String documentId = created.getDocumentId();

            if (!text.isBlank()) {
                GoogleDocsMarkdownRenderer.RenderedDocument rendered = MARKDOWN_RENDERER.render(text);
                List<Request> requests = new ArrayList<>();
                requests.add(new Request().setInsertText(new InsertTextRequest()
                        .setText(rendered.text())
                        .setLocation(new Location().setIndex(1))));
                requests.addAll(rendered.requests());
                google.docs().documents().batchUpdate(documentId,
                        new BatchUpdateDocumentRequest().setRequests(requests)).execute();
            }

            String shareError = google.shareFile(documentId,
                    optionalString(arguments, "share_with", google.defaultShareWith()), "writer");

            StringBuilder sb = new StringBuilder();
            sb.append("Created Google Doc '").append(title).append("'.\n");
            sb.append("documentId: ").append(documentId).append('\n');
            sb.append("url: ").append(GoogleIds.docUrl(documentId));
            if (shareError != null) {
                sb.append("\nWarning: ").append(shareError);
            }
            return sb.toString();
        });
    }
}
