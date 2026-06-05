package com.example.integration.google;

import com.google.api.services.docs.v1.model.Request;
import com.google.api.services.docs.v1.model.TextStyle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleDocsMarkdownRendererTest {

    private final GoogleDocsMarkdownRenderer renderer = new GoogleDocsMarkdownRenderer();

    @Test
    void rendersMarkdownIntoStructuredDocsRequests() {
        String markdown = """
                # AMD Stock Analysis - June 2026

                ## 1. Price Overview (1-year)
                - **Latest Close:** $542.52
                - *52-Week High / Low:* $542.52 / $115.69

                > **Note:** Forward P/E indicates the market expects strong earnings growth.

                | Indicator | Latest Value | Interpretation |
                |-----------|--------------|----------------|
                | **Annualized Volatility** | 0.6507 (65.1%) | High price swing risk |

                ```text
                code sample
                ```
                """;

        GoogleDocsMarkdownRenderer.RenderedDocument rendered = renderer.render(markdown);

        assertThat(rendered.text())
                .doesNotContain("# ", "|-----------", "**", "```")
                .contains("AMD Stock Analysis - June 2026")
                .contains("Latest Close: $542.52")
                .contains("Annualized Volatility")
                .contains("code sample");

        assertThat(requestKinds(rendered.requests()))
                .contains("updateParagraphStyle", "createParagraphBullets", "updateTextStyle");
        assertThat(rendered.requests())
                .anyMatch(request -> request.getUpdateParagraphStyle() != null
                        && "HEADING_1".equals(request.getUpdateParagraphStyle().getParagraphStyle().getNamedStyleType()));
        assertThat(rendered.requests())
                .anyMatch(request -> request.getCreateParagraphBullets() != null);
        assertThat(rendered.requests())
                .anyMatch(request -> request.getUpdateTextStyle() != null
                        && Boolean.TRUE.equals(request.getUpdateTextStyle().getTextStyle().getBold()));
        assertThat(rendered.requests())
                .anyMatch(request -> request.getUpdateTextStyle() != null
                        && request.getUpdateTextStyle().getTextStyle().getWeightedFontFamily() != null
                        && "Courier New".equals(request.getUpdateTextStyle().getTextStyle()
                        .getWeightedFontFamily().getFontFamily()));
        assertThat(rendered.requests())
                .anyMatch(request -> request.getUpdateParagraphStyle() != null
                        && request.getUpdateParagraphStyle().getParagraphStyle().getBorderLeft() != null);
    }

    @Test
    void rendersNullAndBlankMarkdownAsEmptyDocument() {
        assertEmpty(renderer.render(null));
        assertEmpty(renderer.render(""));
        assertEmpty(renderer.render("   \n\t"));
    }

    @Test
    void rendersPlainTextWithoutExtraRequests() {
        GoogleDocsMarkdownRenderer.RenderedDocument rendered = renderer.render("Plain text with café, 東京, and 😀.");

        assertThat(rendered.text()).isEqualTo("Plain text with café, 東京, and 😀.\n");
        assertThat(rendered.requests()).isEmpty();
    }

    @Test
    void rendersNestedInlineFormatting() {
        GoogleDocsMarkdownRenderer.RenderedDocument rendered =
                renderer.render("This has **bold and _italic_** text.");

        assertThat(rendered.text()).isEqualTo("This has bold and italic text.\n");
        assertThat(requestKinds(rendered.requests()))
                .containsExactly("updateTextStyle", "updateTextStyle");
        assertThat(textStyle(rendered.requests().get(0))).isNotNull();
        assertThat(Boolean.TRUE.equals(textStyle(rendered.requests().get(0)).getItalic())).isTrue();
        assertThat(Boolean.TRUE.equals(textStyle(rendered.requests().get(1)).getBold())).isTrue();
    }

    @Test
    void rendersCodeBlockWithExactRequestOrder() {
        GoogleDocsMarkdownRenderer.RenderedDocument rendered = renderer.render("""
                ```text
                code sample
                ```
                """);

        assertThat(rendered.text()).isEqualTo("code sample\n");
        assertThat(requestKinds(rendered.requests()))
                .containsExactly("updateTextStyle", "updateParagraphStyle");
        assertThat(rendered.requests()).hasSize(2);
    }

    @Test
    void rendersUnclosedCodeFenceAsCodeBlock() {
        GoogleDocsMarkdownRenderer.RenderedDocument rendered = renderer.render("""
                ```text
                still code
                """);

        assertThat(rendered.text()).isEqualTo("still code\n\n");
        assertThat(requestKinds(rendered.requests()))
                .containsExactly("updateTextStyle", "updateParagraphStyle");
    }

    @Test
    void preservesExoticUnicodeInPlainText() {
        GoogleDocsMarkdownRenderer.RenderedDocument rendered =
                renderer.render("𝛑, 東京, café, and 👩‍💻 should survive.");

        assertThat(rendered.text()).isEqualTo("𝛑, 東京, café, and 👩‍💻 should survive.\n");
        assertThat(rendered.requests()).isEmpty();
    }

    private void assertEmpty(GoogleDocsMarkdownRenderer.RenderedDocument rendered) {
        assertThat(rendered.text()).isEmpty();
        assertThat(rendered.requests()).isEmpty();
    }

    private List<String> requestKinds(List<Request> requests) {
        return requests.stream()
                .map(this::requestKind)
                .toList();
    }

    private String requestKind(Request request) {
        if (request.getUpdateTextStyle() != null) {
            return "updateTextStyle";
        }
        if (request.getUpdateParagraphStyle() != null) {
            return "updateParagraphStyle";
        }
        if (request.getCreateParagraphBullets() != null) {
            return "createParagraphBullets";
        }
        return "unknown";
    }

    private TextStyle textStyle(Request request) {
        return request.getUpdateTextStyle() == null ? null : request.getUpdateTextStyle().getTextStyle();
    }
}
