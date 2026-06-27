package com.example.integration.google;

import com.google.api.services.docs.v1.model.Color;
import com.google.api.services.docs.v1.model.CreateParagraphBulletsRequest;
import com.google.api.services.docs.v1.model.Dimension;
import com.google.api.services.docs.v1.model.Link;
import com.google.api.services.docs.v1.model.OptionalColor;
import com.google.api.services.docs.v1.model.ParagraphBorder;
import com.google.api.services.docs.v1.model.ParagraphStyle;
import com.google.api.services.docs.v1.model.Range;
import com.google.api.services.docs.v1.model.Request;
import com.google.api.services.docs.v1.model.RgbColor;
import com.google.api.services.docs.v1.model.Shading;
import com.google.api.services.docs.v1.model.TextStyle;
import com.google.api.services.docs.v1.model.WeightedFontFamily;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a small, practical Markdown subset into Google Docs API requests.
 *
 * <p>The Google Docs API is structural rather than Markdown-native, so this renderer converts
 * Markdown blocks into real Docs constructs such as headings, bullets, block quotes, and formatted
 * text spans. The goal is not to be a full CommonMark implementation; it is to render the kinds of
 * content Bouw tends to generate into a clean, readable document.
 */
public final class GoogleDocsMarkdownRenderer {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)([-*+])\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)$");
    private static final Pattern FENCE = Pattern.compile("^```.*$");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^\\s*(?:---+|\\*\\*\\*+|___+)\\s*$");

    private static final String MONO_FONT = "Courier New";
    private static final String BULLET_PRESET = "BULLET_DISC_CIRCLE_SQUARE";

    public RenderedDocument render(String markdown) {
        String normalized = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = normalized.isEmpty() ? List.of() : List.of(normalized.split("\n", -1));

        State state = new State();
        List<String> paragraphLines = new ArrayList<>();

        for (int i = 0; i < lines.size(); ) {
            String line = lines.get(i);

            if (state.inCodeBlock) {
                if (FENCE.matcher(line.trim()).matches()) {
                    renderCodeBlock(state);
                    i++;
                    continue;
                }
                state.codeLines.add(line);
                i++;
                continue;
            }

            if (line.trim().isEmpty()) {
                flushParagraph(state, paragraphLines);
                i++;
                continue;
            }

            if (FENCE.matcher(line.trim()).matches()) {
                flushParagraph(state, paragraphLines);
                state.inCodeBlock = true;
                state.codeLines.clear();
                i++;
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flushParagraph(state, paragraphLines);
                renderHeading(state, heading.group(1).length(), heading.group(2));
                i++;
                continue;
            }

            if (HORIZONTAL_RULE.matcher(line).matches()) {
                flushParagraph(state, paragraphLines);
                renderDivider(state);
                i++;
                continue;
            }

            if (isTableStart(lines, i)) {
                flushParagraph(state, paragraphLines);
                i = renderTable(state, lines, i);
                continue;
            }

            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                flushParagraph(state, paragraphLines);
                i = renderUnorderedList(state, lines, i);
                continue;
            }

            Matcher ordered = ORDERED.matcher(line);
            if (ordered.matches()) {
                flushParagraph(state, paragraphLines);
                i = renderOrderedList(state, lines, i);
                continue;
            }

            if (line.trim().startsWith(">")) {
                flushParagraph(state, paragraphLines);
                i = renderBlockQuote(state, lines, i);
                continue;
            }

            paragraphLines.add(line);
            i++;
        }

        flushParagraph(state, paragraphLines);

        if (state.inCodeBlock) {
            renderCodeBlock(state);
        }

        return new RenderedDocument(state.text.toString(), List.copyOf(state.requests));
    }

    private void flushParagraph(State state, List<String> paragraphLines) {
        if (paragraphLines.isEmpty()) {
            return;
        }

        String paragraphMarkdown = String.join(" ", paragraphLines).trim();
        paragraphLines.clear();
        if (paragraphMarkdown.isEmpty()) {
            return;
        }

        InlineResult inline = parseInline(paragraphMarkdown);
        int start = state.cursor;
        state.text.append(inline.text()).append('\n');
        state.cursor += inline.text().length() + 1;

        applyInlineStyles(state, start, inline);
    }

    private void renderHeading(State state, int level, String markdown) {
        InlineResult inline = parseInline(markdown.trim());
        int start = state.cursor;
        state.text.append(inline.text()).append('\n');
        state.cursor += inline.text().length() + 1;

        applyInlineStyles(state, start, inline);

        String namedStyle = "HEADING_" + Math.min(Math.max(level, 1), 6);
        state.requests.add(updateParagraphStyle(start, state.cursor, new ParagraphStyle()
                .setNamedStyleType(namedStyle)
                .setSpaceAbove(pt(8))
                .setSpaceBelow(pt(4)), "namedStyleType,spaceAbove,spaceBelow"));
    }

    private void renderDivider(State state) {
        int start = state.cursor;
        state.text.append("────────────────────────\n");
        state.cursor += "────────────────────────\n".length();
        state.requests.add(updateTextStyle(start, state.cursor,
                new TextStyle().setForegroundColor(gray(0.55f)).setItalic(true),
                "foregroundColor,italic"));
    }

    private int renderBlockQuote(State state, List<String> lines, int index) {
        int start = state.cursor;
        int i = index;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (!line.trim().startsWith(">")) {
                break;
            }
            String stripped = stripBlockQuote(line);
            InlineResult inline = parseInline(stripped);
            int lineStart = state.cursor;
            state.text.append(inline.text()).append('\n');
            state.cursor += inline.text().length() + 1;
            applyInlineStyles(state, lineStart, inline);
            i++;
        }

        state.requests.add(updateParagraphStyle(start, state.cursor, new ParagraphStyle()
                .setIndentStart(pt(18))
                .setSpaceAbove(pt(4))
                .setSpaceBelow(pt(4))
                .setBorderLeft(new ParagraphBorder()
                        .setColor(gray(0.7f))
                        .setDashStyle("SOLID")
                        .setPadding(pt(8))
                        .setWidth(pt(1))), "indentStart,spaceAbove,spaceBelow,borderLeft"));
        return i;
    }

    private void renderCodeBlock(State state) {
        if (!state.inCodeBlock) {
            return;
        }

        String code = String.join("\n", state.codeLines);
        if (!code.isEmpty()) {
            int codeStart = state.cursor;
            state.text.append(code).append('\n');
            state.cursor += code.length() + 1;

            state.requests.add(updateTextStyle(codeStart, state.cursor,
                    new TextStyle()
                            .setWeightedFontFamily(new WeightedFontFamily().setFontFamily(MONO_FONT))
                            .setFontSize(pt(10))
                            .setBackgroundColor(grayBackground(0.96f)),
                    "weightedFontFamily,fontSize,backgroundColor"));
            state.requests.add(updateParagraphStyle(codeStart, state.cursor, new ParagraphStyle()
                    .setIndentStart(pt(18))
                    .setSpaceAbove(pt(6))
                    .setSpaceBelow(pt(6))
                    .setShading(new Shading().setBackgroundColor(grayBackground(0.96f))), "indentStart,spaceAbove,spaceBelow,shading"));
        }

        state.inCodeBlock = false;
        state.codeLines.clear();
    }

    private int renderUnorderedList(State state, List<String> lines, int index) {
        int start = state.cursor;
        int i = index;
        while (i < lines.size()) {
            Matcher bullet = BULLET.matcher(lines.get(i));
            if (!bullet.matches()) {
                break;
            }

            int level = indentLevel(bullet.group(1));
            String markdown = bullet.group(3);
            InlineResult inline = parseInline(markdown.trim());
            int lineStart = state.cursor;
            String prefix = "\t".repeat(Math.max(0, level));
            state.text.append(prefix).append(inline.text()).append('\n');
            state.cursor += prefix.length() + inline.text().length() + 1;
            applyInlineStyles(state, lineStart + prefix.length(), inline);
            i++;
        }

        state.requests.add(createBullets(start, state.cursor, BULLET_PRESET));
        return i;
    }

    private int renderOrderedList(State state, List<String> lines, int index) {
        int i = index;
        while (i < lines.size()) {
            Matcher ordered = ORDERED.matcher(lines.get(i));
            if (!ordered.matches()) {
                break;
            }

            int level = indentLevel(ordered.group(1));
            String markdown = ordered.group(3);
            InlineResult inline = parseInline(markdown.trim());
            String prefix = ordered.group(2) + ". ";
            String tabs = "\t".repeat(Math.max(0, level));
            int lineStart = state.cursor;
            state.text.append(tabs).append(prefix).append(inline.text()).append('\n');
            state.cursor += tabs.length() + prefix.length() + inline.text().length() + 1;
            applyInlineStyles(state, lineStart + tabs.length() + prefix.length(), inline);
            i++;
        }
        return i;
    }

    private int renderTable(State state, List<String> lines, int index) {
        int i = index;
        List<List<String>> rows = new ArrayList<>();
        while (i < lines.size() && isTableLine(lines.get(i))) {
            rows.add(parseTableRow(lines.get(i)));
            i++;
        }

        if (rows.size() < 2) {
            // Not a real table after all. Fall back to plain paragraphs.
            for (List<String> row : rows) {
                if (!row.isEmpty()) {
                    InlineResult inline = parseInline(String.join(" ", row));
                    int lineStart = state.cursor;
                    state.text.append(inline.text()).append('\n');
                    state.cursor += inline.text().length() + 1;
                    applyInlineStyles(state, lineStart, inline);
                }
            }
            return i;
        }

        rows.remove(1); // Remove the markdown separator row.
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        List<Integer> widths = new ArrayList<>(columns);
        for (int c = 0; c < columns; c++) {
            int width = 0;
            for (List<String> row : rows) {
                if (c < row.size()) {
                    width = Math.max(width, row.get(c).length());
                }
            }
            widths.add(width);
        }

        int tableStart = state.cursor;
        int headerStart = -1;
        int headerEnd = -1;

        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            StringBuilder renderedRow = new StringBuilder();
            int rowTextStart = state.cursor;
            for (int c = 0; c < columns; c++) {
                String cell = c < row.size() ? row.get(c) : "";
                String plain = parseInline(cell).text().trim();
                if (c > 0) {
                    renderedRow.append("  ");
                }
                renderedRow.append(padRight(plain, widths.get(c)));
            }
            if (r == 0) {
                headerStart = rowTextStart;
            }
            state.text.append(renderedRow).append('\n');
            state.cursor += renderedRow.length() + 1;
            if (r == 0) {
                headerEnd = state.cursor;
            }
        }

        state.requests.add(updateTextStyle(tableStart, state.cursor,
                new TextStyle()
                        .setWeightedFontFamily(new WeightedFontFamily().setFontFamily(MONO_FONT))
                        .setFontSize(pt(10)),
                "weightedFontFamily,fontSize"));
        if (headerStart >= 0 && headerEnd > headerStart) {
            state.requests.add(updateTextStyle(headerStart, headerEnd,
                    new TextStyle().setBold(true), "bold"));
        }
        state.requests.add(updateParagraphStyle(tableStart, state.cursor,
                new ParagraphStyle().setSpaceAbove(pt(4)).setSpaceBelow(pt(4)),
                "spaceAbove,spaceBelow"));
        return i;
    }

    private boolean isTableStart(List<String> lines, int index) {
        if (index + 1 >= lines.size()) {
            return false;
        }
        return isTableLine(lines.get(index)) && isTableSeparator(lines.get(index + 1));
    }

    private boolean isTableLine(String line) {
        return line.contains("|");
    }

    private boolean isTableSeparator(String line) {
        String trimmed = line.trim();
        if (!trimmed.contains("-")) {
            return false;
        }
        String[] parts = stripOuterPipes(trimmed).split("\\|");
        if (parts.length < 2) {
            return false;
        }
        for (String part : parts) {
            String cleaned = part.trim();
            if (!cleaned.matches(":?-{3,}:?")) {
                return false;
            }
        }
        return true;
    }

    private List<String> parseTableRow(String line) {
        String[] parts = stripOuterPipes(line.trim()).split("\\|", -1);
        List<String> row = new ArrayList<>(parts.length);
        for (String part : parts) {
            row.add(part.trim());
        }
        return row;
    }

    private String stripOuterPipes(String value) {
        String stripped = value;
        if (stripped.startsWith("|")) {
            stripped = stripped.substring(1);
        }
        if (stripped.endsWith("|")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private String stripBlockQuote(String line) {
        String stripped = line.trim();
        if (stripped.startsWith(">")) {
            stripped = stripped.substring(1);
        }
        if (stripped.startsWith(" ")) {
            stripped = stripped.substring(1);
        }
        return stripped;
    }

    private int indentLevel(String indentation) {
        int tabs = 0;
        int spaces = 0;
        for (char c : indentation.toCharArray()) {
            if (c == '\t') {
                tabs++;
            } else if (c == ' ') {
                spaces++;
            }
        }
        return tabs + spaces / 2;
    }

    private String padRight(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private void applyInlineStyles(State state, int paragraphStart, InlineResult inline) {
        for (InlineSpan span : inline.spans()) {
            int start = paragraphStart + span.start();
            int end = paragraphStart + span.end();
            switch (span.kind()) {
                case BOLD -> state.requests.add(updateTextStyle(start, end,
                        new TextStyle().setBold(true), "bold"));
                case ITALIC -> state.requests.add(updateTextStyle(start, end,
                        new TextStyle().setItalic(true), "italic"));
                case CODE -> state.requests.add(updateTextStyle(start, end,
                        new TextStyle()
                                .setWeightedFontFamily(new WeightedFontFamily().setFontFamily(MONO_FONT))
                                .setFontSize(pt(10))
                                .setBackgroundColor(grayBackground(0.94f)),
                        "weightedFontFamily,fontSize,backgroundColor"));
                case LINK -> state.requests.add(updateTextStyle(start, end,
                        new TextStyle()
                                .setLink(new Link().setUrl(span.value()))
                                .setUnderline(true),
                        "link,underline"));
            }
        }
    }

    private Request updateTextStyle(int start, int end, TextStyle style, String fields) {
        return new Request().setUpdateTextStyle(new com.google.api.services.docs.v1.model.UpdateTextStyleRequest()
                .setRange(range(start, end))
                .setTextStyle(style)
                .setFields(fields));
    }

    private Request updateParagraphStyle(int start, int end, ParagraphStyle style, String fields) {
        return new Request().setUpdateParagraphStyle(new com.google.api.services.docs.v1.model.UpdateParagraphStyleRequest()
                .setRange(range(start, end))
                .setParagraphStyle(style)
                .setFields(fields));
    }

    private Request createBullets(int start, int end, String bulletPreset) {
        return new Request().setCreateParagraphBullets(new CreateParagraphBulletsRequest()
                .setRange(range(start, end))
                .setBulletPreset(bulletPreset));
    }

    private Range range(int start, int end) {
        return new Range().setStartIndex(start).setEndIndex(end);
    }

    private Dimension pt(double magnitude) {
        return new Dimension().setMagnitude(magnitude).setUnit("PT");
    }

    private OptionalColor gray(float value) {
        return new OptionalColor().setColor(new Color().setRgbColor(new RgbColor()
                .setRed(value).setGreen(value).setBlue(value)));
    }

    private OptionalColor grayBackground(float value) {
        return gray(value);
    }

    private InlineResult parseInline(String input) {
        return parseInline(input, 0, input.length());
    }

    private InlineResult parseInline(String input, int start, int end) {
        StringBuilder out = new StringBuilder();
        List<InlineSpan> spans = new ArrayList<>();
        int i = start;
        while (i < end) {
            char ch = input.charAt(i);

            if (ch == '\\' && i + 1 < end) {
                out.append(input.charAt(i + 1));
                i += 2;
                continue;
            }

            if (ch == '`') {
                int close = findClosing(input, '`', i + 1, end);
                if (close > i) {
                    int spanStart = out.length();
                    String content = input.substring(i + 1, close);
                    InlineResult inner = parseInline(content);
                    out.append(inner.text());
                    spans.addAll(offsetSpans(inner.spans(), spanStart));
                    spans.add(new InlineSpan(spanStart, out.length(), SpanKind.CODE, null));
                    i = close + 1;
                    continue;
                }
            }

            if (i + 1 < end && (input.startsWith("**", i) || input.startsWith("__", i))) {
                String marker = input.startsWith("**", i) ? "**" : "__";
                int close = findMatchingPair(input, marker, i + 2, end);
                if (close > i) {
                    int spanStart = out.length();
                    InlineResult inner = parseInline(input, i + 2, close);
                    out.append(inner.text());
                    spans.addAll(offsetSpans(inner.spans(), spanStart));
                    spans.add(new InlineSpan(spanStart, out.length(), SpanKind.BOLD, null));
                    i = close + 2;
                    continue;
                }
            }

            if (ch == '[') {
                int closeLabel = findClosing(input, ']', i + 1, end);
                if (closeLabel > i && closeLabel + 1 < end && input.charAt(closeLabel + 1) == '(') {
                    int closeUrl = findClosing(input, ')', closeLabel + 2, end);
                    if (closeUrl > closeLabel) {
                        int spanStart = out.length();
                        InlineResult label = parseInline(input, i + 1, closeLabel);
                        out.append(label.text());
                        spans.addAll(offsetSpans(label.spans(), spanStart));
                        spans.add(new InlineSpan(spanStart, out.length(), SpanKind.LINK,
                                input.substring(closeLabel + 2, closeUrl)));
                        i = closeUrl + 1;
                        continue;
                    }
                }
            }

            if (ch == '*' || ch == '_') {
                int close = findClosing(input, ch, i + 1, end);
                if (close > i) {
                    int spanStart = out.length();
                    InlineResult inner = parseInline(input, i + 1, close);
                    out.append(inner.text());
                    spans.addAll(offsetSpans(inner.spans(), spanStart));
                    spans.add(new InlineSpan(spanStart, out.length(), SpanKind.ITALIC, null));
                    i = close + 1;
                    continue;
                }
            }

            out.append(ch);
            i++;
        }

        return new InlineResult(out.toString(), spans);
    }

    private List<InlineSpan> offsetSpans(List<InlineSpan> spans, int offset) {
        List<InlineSpan> out = new ArrayList<>(spans.size());
        for (InlineSpan span : spans) {
            out.add(new InlineSpan(span.start() + offset, span.end() + offset, span.kind(), span.value()));
        }
        return out;
    }

    private int findClosing(String input, char target, int start, int end) {
        for (int i = start; i < end; i++) {
            if (input.charAt(i) == target) {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingPair(String input, String marker, int start, int end) {
        int i = start;
        while (i < end - 1) {
            if (input.startsWith(marker, i)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static final class State {
        private final StringBuilder text = new StringBuilder();
        private final List<Request> requests = new ArrayList<>();
        private int cursor = 1;
        private boolean inCodeBlock;
        private final List<String> codeLines = new ArrayList<>();
    }

    public record RenderedDocument(String text, List<Request> requests) {
        public RenderedDocument {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(requests, "requests");
        }
    }

    private record InlineResult(String text, List<InlineSpan> spans) {}

    private enum SpanKind {
        BOLD,
        ITALIC,
        CODE,
        LINK
    }

    private record InlineSpan(int start, int end, SpanKind kind, String value) {}
}
