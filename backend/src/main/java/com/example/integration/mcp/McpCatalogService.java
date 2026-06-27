package com.example.integration.mcp;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Serves the curated MCP "marketplace" catalog.
 *
 * <p>Phase 2 ships a small built-in list of well-known servers so users can add one with a click
 * instead of hand-typing endpoints. It contains no secrets — just public connection metadata that
 * pre-fills the Add Server form; the user still supplies credentials / authorizes.
 *
 * <p>Extension point: a future "registry import" can replace or augment {@link #catalog()} by fetching
 * the official MCP registry and mapping entries into {@link McpCatalogEntry}, without changing callers.
 */
@Service
public class McpCatalogService {

    private static final List<McpCatalogEntry> CATALOG = List.of(
            new McpCatalogEntry(
                    "github",
                    "GitHub",
                    "Issues, pull requests, and repository search via GitHub's hosted MCP server.",
                    "github",
                    "STREAMABLE_HTTP",
                    "https://api.githubcopilot.com/mcp/",
                    "OAUTH",
                    "https://github.com/github/github-mcp-server"),
            new McpCatalogEntry(
                    "linear",
                    "Linear",
                    "Create and update Linear issues, projects, and comments.",
                    "linear",
                    "STREAMABLE_HTTP",
                    "https://mcp.linear.app/mcp",
                    "OAUTH",
                    "https://linear.app/docs/mcp"),
            new McpCatalogEntry(
                    "sentry",
                    "Sentry",
                    "Query Sentry issues and events for debugging context.",
                    "sentry",
                    "STREAMABLE_HTTP",
                    "https://mcp.sentry.dev/mcp",
                    "OAUTH",
                    "https://docs.sentry.io/product/sentry-mcp/"));

    public List<McpCatalogEntry> catalog() {
        return CATALOG;
    }
}
