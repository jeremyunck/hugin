package com.example.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/** Shared parsing of MCP {@code tools/call} results, used by every transport. */
final class McpResults {

    private McpResults() {
    }

    /**
     * Concatenates the text of all {@code text} content blocks in a {@code tools/call} result. Non-text
     * blocks (image, resource, …) are noted without dumping bytes; a structured-only result falls back
     * to a compact JSON rendering.
     */
    static String extractTextContent(JsonNode result) {
        JsonNode content = result.path("content");
        if (!content.isArray() || content.isEmpty()) {
            JsonNode structured = result.path("structuredContent");
            if (!structured.isMissingNode() && !structured.isNull()) {
                return structured.toString();
            }
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            if ("text".equals(type)) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(block.path("text").asText(""));
            } else if (!type.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("[").append(type).append(" content omitted]");
            }
        }
        return sb.toString();
    }
}
