package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.agent.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Conducts "deep research" on a topic by fanning out several focused web searches, then curating
 * what comes back into a single source-backed brief the agent can digest and turn into a report.
 *
 * <p>Where {@code web_search} answers one query, this tool investigates a topic from multiple angles
 * (overview, recent developments, opposing views, data, implications — or caller-supplied focus
 * areas), gathers the sources each search cites, de-duplicates them across angles, and notes which
 * sections drew on each source. The output is deliberately raw research material plus a consolidated
 * citation list: the agent is expected to read it and write the actual report, citing the URLs.
 */
@Component
public class DeepResearchTool implements LocalTool {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchTool.class);

    private static final String SYSTEM_PROMPT = "You are a research assistant gathering raw source "
            + "material on a topic. Search the web and report the most relevant, credible, and recent "
            + "findings for the requested angle. Be specific and factual, prefer primary and "
            + "authoritative sources, and include the direct source URL for every claim. Do not write "
            + "a polished final report — just surface the key findings and the sources behind them.";

    /** Default angles used when the caller does not supply their own focus areas. */
    private static final List<String> DEFAULT_FOCUS_AREAS = List.of(
            "Overview, background, and key facts",
            "Recent developments, news, and current state",
            "Different perspectives, debates, and criticisms",
            "Key data, statistics, and concrete evidence",
            "Practical implications, applications, and what comes next");

    private static final int MAX_FOCUS_AREAS = 6;
    private static final int DEFAULT_MAX_SOURCES = 15;
    private static final int MAX_TOKENS_PER_SEARCH = 1024;

    private final OpenRouterSearchClient searchClient;
    private final String model;

    @Autowired
    public DeepResearchTool(
            OpenRouterSearchClient searchClient,
            @Value("${research.model:${web.search.model:perplexity/sonar}}") String model) {
        this.searchClient = searchClient;
        this.model = model;
    }

    @Override
    public boolean isAvailable() {
        // Backed by the same OpenRouter search integration as web_search; only advertise it when the
        // API key is configured so the model never sees an unconfigured capability.
        return searchClient.isConfigured();
    }

    @Override
    public String name() {
        return "deep_research";
    }

    @Override
    public String description() {
        return "Research a topic in depth: runs several focused web searches across different angles, "
                + "gathers and de-duplicates the sources they cite, and returns a curated, "
                + "source-backed brief to digest and write a report from. Prefer this over web_search "
                + "when the user asks you to research, investigate, or write a report on a topic.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "topic", Map.of(
                                "type", "string",
                                "description", "The topic or question to research in depth."),
                        "focus_areas", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Optional specific angles, questions, or subtopics to "
                                        + "investigate (one search per item, up to " + MAX_FOCUS_AREAS
                                        + "). If omitted, a default set of research angles is used."),
                        "max_sources", Map.of(
                                "type", "integer",
                                "description", "Optional cap on the number of unique sources in the "
                                        + "consolidated list (default " + DEFAULT_MAX_SOURCES + ").")),
                "required", List.of("topic"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        return runResearch(arguments, model);
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        // A user can pick a research model in Settings; it arrives per-request via the context and
        // overrides the server-wide default only for this run. Fall back to the configured model when
        // nothing was selected.
        String override = ctx == null ? null : ctx.researchModel();
        return runResearch(arguments, override != null && !override.isBlank() ? override : model);
    }

    private String runResearch(Map<String, Object> arguments, String effectiveModel) throws Exception {
        if (!searchClient.isConfigured()) {
            return "deep_research is unavailable: OPEN_ROUTER_API_KEY is not set.";
        }

        String topic = requiredString(arguments, "topic");
        List<String> focusAreas = resolveFocusAreas(arguments);
        int maxSources = Math.max(1, optionalInt(arguments, "max_sources", DEFAULT_MAX_SOURCES));

        // Each unique source URL maps to the title we first saw and the sections that cited it, so the
        // consolidated list can show provenance across the whole investigation.
        Map<String, ConsolidatedSource> consolidated = new LinkedHashMap<>();
        List<String> sectionBlocks = new ArrayList<>();
        int succeeded = 0;

        for (int i = 0; i < focusAreas.size(); i++) {
            int section = i + 1;
            String focus = focusAreas.get(i);
            String query = focus + " — regarding: " + topic;

            StringBuilder block = new StringBuilder();
            block.append("## ").append(section).append(". ").append(focus).append("\n");
            try {
                OpenRouterSearchClient.SearchResult result =
                        searchClient.search(effectiveModel, SYSTEM_PROMPT, query, MAX_TOKENS_PER_SEARCH);
                succeeded++;
                block.append(result.content().strip()).append("\n");
                appendSectionSources(block, result.sources());
                recordSources(consolidated, result.sources(), section);
            } catch (OpenRouterSearchClient.SearchException e) {
                log.warn("deep_research: search for angle '{}' failed: {}", focus, e.getMessage());
                block.append("_(search failed for this angle: ").append(e.getMessage()).append(")_\n");
            }
            sectionBlocks.add(block.toString());
        }

        if (succeeded == 0) {
            return "Deep research on \"" + topic + "\" failed: none of the " + focusAreas.size()
                    + " searches returned results. Check the OpenRouter configuration and try again.";
        }

        return buildBrief(topic, focusAreas.size(), succeeded, sectionBlocks, consolidated, maxSources);
    }

    /** Builds the search angles: caller-supplied focus areas (capped) or the default set. */
    private List<String> resolveFocusAreas(Map<String, Object> arguments) {
        Object raw = arguments.get("focus_areas");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            List<String> focusAreas = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    focusAreas.add(item.toString().strip());
                }
                if (focusAreas.size() == MAX_FOCUS_AREAS) {
                    break;
                }
            }
            if (!focusAreas.isEmpty()) {
                return focusAreas;
            }
        }
        return DEFAULT_FOCUS_AREAS;
    }

    private void appendSectionSources(StringBuilder block, List<OpenRouterSearchClient.Source> sources) {
        if (sources.isEmpty()) {
            return;
        }
        block.append("\nSources for this section:\n");
        int i = 1;
        for (OpenRouterSearchClient.Source source : sources) {
            block.append("[").append(i++).append("] ");
            if (source.title() != null && !source.title().isBlank()) {
                block.append(source.title()).append(" - ");
            }
            block.append(source.url()).append("\n");
        }
    }

    private void recordSources(Map<String, ConsolidatedSource> consolidated,
                               List<OpenRouterSearchClient.Source> sources, int section) {
        for (OpenRouterSearchClient.Source source : sources) {
            ConsolidatedSource entry = consolidated.computeIfAbsent(
                    source.url(), url -> new ConsolidatedSource(source.title()));
            entry.sections().add(section);
        }
    }

    private String buildBrief(String topic, int angleCount, int succeeded, List<String> sectionBlocks,
                              Map<String, ConsolidatedSource> consolidated, int maxSources) {
        StringBuilder out = new StringBuilder();
        out.append("# Research brief: ").append(topic).append("\n\n");
        out.append("Assembled from ").append(succeeded).append(" of ").append(angleCount)
                .append(" targeted web searches. Use the curated findings and sources below to write a "
                        + "well-structured, well-cited report; verify key claims against the listed "
                        + "sources and cite them by URL.\n\n");

        for (String block : sectionBlocks) {
            out.append(block).append("\n");
        }

        out.append("---\n");
        int total = consolidated.size();
        int shown = Math.min(total, maxSources);
        out.append("## Consolidated sources (").append(shown);
        if (total > shown) {
            out.append(" of ").append(total);
        }
        out.append(" unique)\n");

        int i = 1;
        for (Map.Entry<String, ConsolidatedSource> entry : consolidated.entrySet()) {
            if (i > maxSources) {
                break;
            }
            ConsolidatedSource source = entry.getValue();
            out.append("[").append(i++).append("] ");
            if (source.title() != null && !source.title().isBlank()) {
                out.append(source.title()).append(" - ");
            }
            out.append(entry.getKey());
            out.append("  (cited in section").append(source.sections().size() > 1 ? "s " : " ");
            out.append(joinSections(source.sections())).append(")\n");
        }
        if (total > shown) {
            out.append("…and ").append(total - shown).append(" more (raise max_sources to include them).\n");
        }
        return out.toString();
    }

    private String joinSections(TreeSet<Integer> sections) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Integer section : sections) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(section);
            first = false;
        }
        return sb.toString();
    }

    /** Title (first seen) and the set of section numbers that cited a given source URL. */
    private record ConsolidatedSource(String title, TreeSet<Integer> sections) {
        ConsolidatedSource(String title) {
            this(title, new TreeSet<>());
        }
    }
}
