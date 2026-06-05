package com.example.agent.tool;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Extracts readable text from a PDF file within the workspace. */
@Component
public class ReadPdfTool implements LocalTool {

    private final Workspace workspace;
    private final int maxChars;
    private final PathDenyList denyList;

    public ReadPdfTool(Workspace workspace, LocalToolProperties properties, PathDenyList denyList) {
        this.workspace = workspace;
        this.maxChars = properties.maxOutputChars();
        this.denyList = denyList;
    }

    @Override
    public String name() {
        return "read_pdf";
    }

    @Override
    public String description() {
        return "Extract readable text from a PDF file in the workspace. "
                + "Supports encrypted PDFs when a password is provided.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "PDF file path, relative to the workspace root."),
                        "password", Map.of(
                                "type", "string",
                                "description", "Optional password for an encrypted PDF.")),
                "required", List.of("path"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) {
        String requested = requiredString(arguments, "path");
        String password = optionalString(arguments, "password", "");
        Workspace ws = ctx.workspace();
        Path file = ws.resolve(requested);
        String relative = ws.relativize(file);

        if (denyList.isDenied(relative)) {
            return "Error: access to '" + requested + "' is denied by configuration.";
        }
        if (!Files.exists(file)) {
            return "Error: file does not exist: " + requested;
        }
        if (Files.isDirectory(file)) {
            return "Error: path is a directory, not a file: " + requested;
        }

        try (PDDocument document = password.isBlank()
                ? Loader.loadPDF(file.toFile())
                : Loader.loadPDF(file.toFile(), password)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            int pageCount = document.getNumberOfPages();
            StringBuilder out = new StringBuilder();
            out.append("Title: ")
                    .append(optionalPdfTitle(document.getDocumentInformation().getTitle(), relative))
                    .append('\n');
            out.append("Pages: ").append(pageCount).append('\n');
            out.append("Encrypted: ").append(document.isEncrypted() ? "yes" : "no").append('\n');
            out.append("Path: ").append(relative).append("\n\n");

            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document).strip();
                out.append("=== Page ").append(page).append(" ===\n");
                out.append(pageText.isBlank() ? "(no extractable text on this page)" : pageText);
                if (page < pageCount) {
                    out.append("\n\n");
                }
                if (out.length() > maxChars) {
                    return truncate(out, maxChars);
                }
            }

            return out.isEmpty() ? "(document is empty)" : out.toString();
        } catch (IOException e) {
            return "Error: failed to read PDF '" + requested + "': " + e.getMessage();
        }
    }

    private static String optionalPdfTitle(String title, String fallback) {
        return (title == null || title.isBlank()) ? fallback : title;
    }

    private static String truncate(StringBuilder out, int maxChars) {
        if (out.length() <= maxChars) {
            return out.toString();
        }
        int omitted = out.length() - maxChars;
        return out.substring(0, maxChars) + "\n... [truncated " + omitted + " characters]";
    }
}
