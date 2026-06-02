package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GoogleIds;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.docs.v1.model.Document;
import com.google.api.services.docs.v1.model.ParagraphElement;
import com.google.api.services.docs.v1.model.StructuralElement;
import com.google.api.services.docs.v1.model.TextRun;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Reads the plain-text content of a Google Doc. */
@Component
public class GoogleDocsReadTool implements LocalTool {

    private final GoogleWorkspaceClientFactory google;

    public GoogleDocsReadTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public String name() {
        return "google_docs_read";
    }

    @Override
    public String description() {
        return "Read and return the plain-text content of a Google Doc. "
                + "Accepts a document id or a full Google Docs URL.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "document_id", Map.of(
                                "type", "string",
                                "description", "The Google Doc id, or a full docs.google.com URL.")),
                "required", List.of("document_id"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String documentId = GoogleIds.extract(requiredString(arguments, "document_id"));
            Document doc = google.docs().documents().get(documentId).execute();

            StringBuilder text = new StringBuilder();
            if (doc.getBody() != null && doc.getBody().getContent() != null) {
                for (StructuralElement element : doc.getBody().getContent()) {
                    if (element.getParagraph() != null && element.getParagraph().getElements() != null) {
                        for (ParagraphElement pe : element.getParagraph().getElements()) {
                            TextRun run = pe.getTextRun();
                            if (run != null && run.getContent() != null) {
                                text.append(run.getContent());
                            }
                        }
                    }
                }
            }

            String body = text.toString();
            return "Title: " + doc.getTitle() + "\n"
                    + "url: " + GoogleIds.docUrl(documentId) + "\n\n"
                    + (body.isBlank() ? "(document is empty)" : body);
        });
    }
}
