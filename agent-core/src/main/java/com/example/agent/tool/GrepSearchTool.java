package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** Searches file contents for lines matching a regular expression (grep-style). */
@Component
public class GrepSearchTool implements LocalTool {

    private static final int DEFAULT_MAX_RESULTS = 200;
    private static final int HARD_MAX_RESULTS = 2000;

    private final Workspace workspace;

    public GrepSearchTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "grep_search";
    }

    @Override
    public String description() {
        return "Search file contents for lines matching a Java regular expression, like grep. "
                + "Returns matches as 'relative/path:line: text'. Optionally restrict to files "
                + "matching a glob (e.g. '*.java'). Common build/VCS directories are skipped.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of(
                                "type", "string",
                                "description", "Java regular expression to search for."),
                        "path", Map.of(
                                "type", "string",
                                "description", "File or directory to search, relative to the workspace root. Defaults to '.'."),
                        "glob", Map.of(
                                "type", "string",
                                "description", "Optional file-name glob filter, e.g. '*.java' or '*.{ts,tsx}'."),
                        "ignore_case", Map.of(
                                "type", "boolean",
                                "description", "Case-insensitive matching. Defaults to false."),
                        "max_results", Map.of(
                                "type", "integer",
                                "description", "Maximum matching lines to return. Defaults to " + DEFAULT_MAX_RESULTS + ".")),
                "required", List.of("pattern"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws IOException {
        String patternText = requiredString(arguments, "pattern");
        String requested = optionalString(arguments, "path", ".");
        String glob = optionalString(arguments, "glob", null);
        boolean ignoreCase = optionalBoolean(arguments, "ignore_case", false);
        int requestedMax = Math.min(optionalInt(arguments, "max_results", DEFAULT_MAX_RESULTS), HARD_MAX_RESULTS);
        final int maxResults = requestedMax <= 0 ? DEFAULT_MAX_RESULTS : requestedMax;

        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText, ignoreCase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (PatternSyntaxException e) {
            return "Error: invalid regular expression: " + e.getMessage();
        }

        Workspace ws = ctx.workspace();
        Path base = ws.resolve(requested);
        if (!Files.exists(base)) {
            return "Error: path does not exist: " + requested;
        }

        PathMatcher globMatcher = glob == null
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + glob);

        List<String> results = new ArrayList<>();
        if (Files.isRegularFile(base)) {
            searchFile(ws, base, pattern, globMatcher, results, maxResults);
        } else {
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> isNotSkipped(base, p))
                        .forEach(p -> searchFile(ws, p, pattern, globMatcher, results, maxResults));
            } catch (UncheckedIOException ignored) {
                // best-effort walk; individual unreadable files are skipped below
            }
        }

        if (results.isEmpty()) {
            return "No matches found.";
        }
        boolean truncated = results.size() > maxResults;
        List<String> shown = truncated ? results.subList(0, maxResults) : results;
        String body = String.join("\n", shown);
        return truncated ? body + "\n... [truncated at " + maxResults + " matches]" : body;
    }

    private static void searchFile(Workspace ws, Path file, Pattern pattern, PathMatcher globMatcher,
                            List<String> results, int maxResults) {
        if (results.size() > maxResults) {
            return;
        }
        Path fileName = file.getFileName();
        if (globMatcher != null && (fileName == null || !globMatcher.matches(fileName))) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            return; // binary or unreadable file — skip
        }
        String relative = ws.relativize(file);
        for (int i = 0; i < lines.size() && results.size() <= maxResults; i++) {
            if (pattern.matcher(lines.get(i)).find()) {
                results.add(relative + ":" + (i + 1) + ": " + lines.get(i).strip());
            }
        }
    }

    private static boolean isNotSkipped(Path base, Path path) {
        for (Path part : base.relativize(path)) {
            if (Workspace.IGNORED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }
}
