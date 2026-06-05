package com.example.integration.google;

import com.google.api.services.docs.v1.model.Request;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleDocsMarkdownRendererTest {

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

        GoogleDocsMarkdownRenderer.RenderedDocument rendered = new GoogleDocsMarkdownRenderer().render(markdown);

        assertThat(rendered.text())
                .doesNotContain("# ", "|-----------", "**", "```")
                .contains("AMD Stock Analysis - June 2026")
                .contains("Latest Close: $542.52")
                .contains("Annualized Volatility")
                .contains("code sample");

        assertThat(rendered.requests()).anyMatch(request ->
                request.getUpdateParagraphStyle() != null
                        && "HEADING_1".equals(request.getUpdateParagraphStyle().getParagraphStyle().getNamedStyleType()));

        assertThat(rendered.requests()).anyMatch(request -> request.getCreateParagraphBullets() != null);

        assertThat(rendered.requests()).anyMatch(request ->
                request.getUpdateTextStyle() != null
                        && Boolean.TRUE.equals(request.getUpdateTextStyle().getTextStyle().getBold()));

        assertThat(rendered.requests()).anyMatch(request ->
                request.getUpdateTextStyle() != null
                        && request.getUpdateTextStyle().getTextStyle().getWeightedFontFamily() != null
                        && "Courier New".equals(request.getUpdateTextStyle().getTextStyle()
                        .getWeightedFontFamily().getFontFamily()));

        assertThat(rendered.requests()).anyMatch(request ->
                request.getUpdateParagraphStyle() != null
                        && request.getUpdateParagraphStyle().getParagraphStyle().getBorderLeft() != null);
    }
}
