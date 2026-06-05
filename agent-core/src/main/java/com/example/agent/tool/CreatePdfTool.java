package com.example.agent.tool;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Creates a new PDF file in the workspace from plain text. */
@Component
public class CreatePdfTool implements LocalTool {

    private static final PDRectangle PAGE_SIZE = PDRectangle.LETTER;
    private static final float MARGIN = 54f;
    private static final float FONT_SIZE = 11f;
    private static final float LINE_HEIGHT = 14f;
    private static final List<Path> FONT_CANDIDATES = List.of(
            Path.of("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"),
            Path.of("/System/Library/Fonts/Supplemental/Arial Unicode MS.ttf"),
            Path.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
            Path.of("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
            Path.of("/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf"));

    private final Workspace workspace;
    private final PathDenyList denyList;

    public CreatePdfTool(Workspace workspace, PathDenyList denyList) {
        this.workspace = workspace;
        this.denyList = denyList;
    }

    @Override
    public String name() {
        return "create_pdf";
    }

    @Override
    public String description() {
        return "Create a PDF file in the workspace from plain text content.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "PDF file path to create, relative to the workspace root."),
                        "content", Map.of(
                                "type", "string",
                                "description", "Plain text content to place into the PDF."),
                        "title", Map.of(
                                "type", "string",
                                "description", "Optional document title metadata.")),
                "required", List.of("path", "content"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws IOException {
        String requested = requiredString(arguments, "path");
        String content = presentString(arguments, "content");
        String title = optionalString(arguments, "title", "Untitled PDF");
        Workspace ws = ctx.workspace();
        Path file = ws.resolve(requested);
        String relative = ws.relativize(file);

        if (denyList.isDenied(relative)) {
            return "Error: access to '" + requested + "' is denied by configuration.";
        }
        if (Files.isDirectory(file)) {
            return "Error: path is a directory, not a file: " + requested;
        }

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        int pagesWritten;
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle(title);

            PDFont font = loadFont(document);
            pagesWritten = writeText(document, content, font);
            document.save(file.toFile());
        }

        return "Wrote PDF to " + relative + " (" + pagesWritten + " page"
                + (pagesWritten == 1 ? "" : "s") + ", " + content.length() + " characters)";
    }

    private PDFont loadFont(PDDocument document) throws IOException {
        for (Path candidate : FONT_CANDIDATES) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                return PDType0Font.load(document, candidate.toFile());
            } catch (IOException e) {
                // Try the next candidate.
            }
        }

        throw new IOException("No Unicode-capable font found for PDF generation.");
    }

    private int writeText(PDDocument document, String content, PDFont font) throws IOException {
        List<String> lines = wrapContent(content, font);
        if (lines.isEmpty()) {
            lines = List.of("");
        }

        int pageCount = 0;
        PDPage page = new PDPage(PAGE_SIZE);
        document.addPage(page);
        pageCount++;

        float topY = PAGE_SIZE.getHeight() - MARGIN;
        float bottomY = MARGIN;
        float y = topY;

        PDPageContentStream stream = new PDPageContentStream(document, page, AppendMode.APPEND, true, true);
        stream.beginText();
        stream.setFont(font, FONT_SIZE);
        stream.newLineAtOffset(MARGIN, y);

        try {
            for (String line : lines) {
                if (line.isEmpty()) {
                    y -= LINE_HEIGHT;
                    if (y < bottomY) {
                        stream.endText();
                        stream.close();
                        page = new PDPage(PAGE_SIZE);
                        document.addPage(page);
                        pageCount++;
                        stream = new PDPageContentStream(document, page, AppendMode.APPEND, true, true);
                        stream.beginText();
                        stream.setFont(font, FONT_SIZE);
                        y = topY;
                        stream.newLineAtOffset(MARGIN, y);
                    } else {
                        stream.newLineAtOffset(0, -LINE_HEIGHT);
                    }
                    continue;
                }

                if (y < bottomY + LINE_HEIGHT) {
                    stream.endText();
                    stream.close();
                    page = new PDPage(PAGE_SIZE);
                    document.addPage(page);
                    pageCount++;
                    stream = new PDPageContentStream(document, page, AppendMode.APPEND, true, true);
                    stream.beginText();
                    stream.setFont(font, FONT_SIZE);
                    y = topY;
                    stream.newLineAtOffset(MARGIN, y);
                }

                stream.showText(line);
                y -= LINE_HEIGHT;
                if (y >= bottomY) {
                    stream.newLineAtOffset(0, -LINE_HEIGHT);
                }
            }
        } finally {
            stream.endText();
            stream.close();
        }

        return pageCount;
    }

    private List<String> wrapContent(String content, PDFont font) throws IOException {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        List<String> wrapped = new ArrayList<>();
        for (String paragraph : normalized.split("\n", -1)) {
            if (paragraph.isBlank()) {
                wrapped.add("");
                continue;
            }
            wrapped.addAll(wrapParagraph(paragraph, font));
        }
        return wrapped;
    }

    private List<String> wrapParagraph(String paragraph, PDFont font) throws IOException {
        float maxWidth = PAGE_SIZE.getWidth() - (2 * MARGIN);
        List<String> wrapped = new ArrayList<>();
        String remaining = paragraph.stripTrailing();

        while (!remaining.isEmpty()) {
            int end = findWrapPoint(remaining, maxWidth, font);
            if (end <= 0 || end >= remaining.length()) {
                wrapped.add(remaining);
                break;
            }

            String line = remaining.substring(0, end).stripTrailing();
            if (line.isEmpty()) {
                line = remaining.substring(0, Math.min(remaining.length(), end));
            }
            wrapped.add(line);
            remaining = remaining.substring(end).stripLeading();
        }

        return wrapped;
    }

    private int findWrapPoint(String text, float maxWidth, PDFont font) throws IOException {
        if (fits(text, maxWidth, font)) {
            return text.length();
        }

        int low = 1;
        int high = text.length();
        int best = 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (fits(text.substring(0, mid), maxWidth, font)) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        for (int i = best; i > 0; i--) {
            if (Character.isWhitespace(text.charAt(i - 1))) {
                return Math.max(1, i - 1);
            }
        }

        return best;
    }

    private boolean fits(String text, float maxWidth, PDFont font) throws IOException {
        return measureWidth(text, font) <= maxWidth;
    }

    private float measureWidth(String text, PDFont font) throws IOException {
        return font.getStringWidth(text) / 1000f * FONT_SIZE;
    }
}
