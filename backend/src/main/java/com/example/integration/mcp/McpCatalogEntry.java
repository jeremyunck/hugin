package com.example.integration.mcp;

/**
 * A curated, well-known MCP server users can add with one click. The catalog is intentionally a small,
 * static list (no secrets) that pre-fills the "Add server" form; users still provide their own
 * credentials and authorize. This is the extension point for a future remote registry import.
 */
public record McpCatalogEntry(
        String id,
        String name,
        String description,
        String suggestedServerName,
        String transport,
        String endpointUrl,
        String authType,
        String docsUrl) {
}
