package com.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds and queries a lightweight repository index containing file paths and symbol-like lines.
 */
@Component
public class RepoIndexTool implements LocalTool {

    private static final int MAX_RESULTS = 100;
    private static final int MAX_FILE_BYTES = 250_000;
    private static final List<String> SYMBOL_PREFIXES = List.of(
            "class ", "interface ", "record ", "enum ", "def ", "function ", "fn ", "public class ",
            "public interface ", "export class ", "export function ", "const ", "let ", "var ");

    private final Workspace workspace;
    private final ObjectMapper objectMapper;

    public RepoIndexTool(Workspace workspace, ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "repo_index";
    }

    @Override
    public String description() {
        return "Build or query a lightweight repository index of files and symbol-like lines. "
                + "Use action='build' to refresh the cache, or action='search' to find likely files "
                + "and declarations by name.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", List.of("build", "search"),
                                "description", "Build the index or search it."),
                        "query", Map.of(
                                "type", "string",
                                "description", "Query text for action='search'."),
                        "max_results", Map.of(
                                "type", "integer",
                                "description", "Maximum results to return.")),
                "required", List.of("action"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        String action = requiredString(arguments, "action").trim().toLowerCase(Locale.ROOT);
        Path indexFile = indexPath(ctx.workspace());
        if ("build".equals(action)) {
            RepoIndex index = buildIndex(ctx.workspace());
            Files.createDirectories(indexFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(indexFile.toFile(), index);
            return "Indexed " + index.files.size() + " file(s) and " + index.symbols.size()
                    + " symbol hint(s) at " + workspace.relativize(indexFile);
        }
        if ("search".equals(action)) {
            String query = requiredString(arguments, "query").toLowerCase(Locale.ROOT);
            int maxResults = Math.max(1, optionalInt(arguments, "max_results", MAX_RESULTS));
            RepoIndex index = ensureIndex(ctx.workspace(), indexFile);
            List<String> matches = index.search(query, maxResults);
            if (matches.isEmpty()) {
                return "No repository index matches for '" + query + "'.";
            }
            return String.join("\n", matches);
        }
        return "Error: unknown action '" + action + "'.";
    }

    private RepoIndex ensureIndex(Workspace workspace, Path indexFile) throws IOException {
        if (Files.exists(indexFile)) {
            return objectMapper.readValue(indexFile.toFile(), RepoIndex.class);
        }
        try {
            RepoIndex index = buildIndex(workspace);
            Files.createDirectories(indexFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(indexFile.toFile(), index);
            return index;
        } catch (Exception e) {
            throw new IOException("Could not build repo index: " + e.getMessage(), e);
        }
    }

    private RepoIndex buildIndex(Workspace workspace) throws IOException {
        Path root = workspace.root();
        List<RepoFile> files = new ArrayList<>();
        List<RepoSymbol> symbols = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> isNotIgnored(root, p))
                    .forEach(path -> {
                        try {
                            long size = Files.size(path);
                            String rel = workspace.relativize(path);
                            files.add(new RepoFile(rel, size, mimeHint(rel)));
                            if (size <= MAX_FILE_BYTES && isTextFile(rel)) {
                                extractSymbols(workspace, path).forEach(symbols::add);
                            }
                        } catch (Exception ignored) {
                            // best-effort
                        }
                    });
        }
        return new RepoIndex(Instant.now().toString(), files, symbols);
    }

    private List<RepoSymbol> extractSymbols(Workspace workspace, Path path) throws IOException {
        List<RepoSymbol> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#")) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (SYMBOL_PREFIXES.stream().anyMatch(lower::startsWith)) {
                result.add(new RepoSymbol(workspace.relativize(path), i + 1, trimmed));
            }
        }
        return result;
    }

    private static boolean isTextFile(String rel) {
        String lower = rel.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".groovy")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".tsx")
                || lower.endsWith(".py") || lower.endsWith(".yml") || lower.endsWith(".yaml")
                || lower.endsWith(".json") || lower.endsWith(".md") || lower.endsWith(".txt")
                || lower.endsWith(".xml") || lower.endsWith(".gradle") || lower.endsWith(".sh")
                || lower.endsWith(".toml") || lower.endsWith(".properties");
    }

    private static boolean isNotIgnored(Path root, Path path) {
        Path rel = root.relativize(path);
        for (Path part : rel) {
            if (Workspace.IGNORED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private Path indexPath(Workspace workspace) {
        return workspace.root().resolve(".hugin").resolve("repo-index.json");
    }

    private static String mimeHint(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".js")) return "javascript";
        if (lower.endsWith(".py")) return "python";
        if (lower.endsWith(".md")) return "markdown";
        return "text";
    }

    public record RepoIndex(String builtAt, List<RepoFile> files, List<RepoSymbol> symbols) {
        public List<String> search(String query, int maxResults) {
            String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
            if (q.isBlank()) {
                return List.of();
            }
            List<String> results = new ArrayList<>();
            for (RepoFile file : files) {
                if (results.size() >= maxResults) break;
                if (file.path().toLowerCase(Locale.ROOT).contains(q)) {
                    results.add(file.path() + " (" + file.kind() + ", " + file.size() + " bytes)");
                }
            }
            for (RepoSymbol symbol : symbols) {
                if (results.size() >= maxResults) break;
                if (symbol.symbol().toLowerCase(Locale.ROOT).contains(q)
                        || symbol.path().toLowerCase(Locale.ROOT).contains(q)) {
                    results.add(symbol.path() + ":" + symbol.line() + " " + symbol.symbol());
                }
            }
            return results.stream().distinct().limit(maxResults).collect(Collectors.toList());
        }
    }

    public record RepoFile(String path, long size, String kind) {}

    public record RepoSymbol(String path, int line, String symbol) {}
}
