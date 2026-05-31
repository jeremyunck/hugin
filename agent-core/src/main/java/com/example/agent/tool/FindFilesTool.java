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
import java.util.stream.Stream;

/**
 * Finds files by name (glob) anywhere under the workspace, complementing {@code grep_search}
 * (which matches file <em>contents</em>) and {@code list_files} (which lists a single directory).
 */
@Component
public class FindFilesTool implements LocalTool {

    private static final int DEFAULT_MAX_RESULTS = 200;
    private static final int HARD_MAX_RESULTS = 2000;

    private final Workspace workspace;

    public FindFilesTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "find_files";
    }

    @Override
    public String description() {
        return "Find files (and optionally directories) by name across the workspace using a glob "
                + "pattern, like 'find -name'. A pattern without '/' (e.g. '*.java', 'pom.xml') matches "
                + "against each entry's name at any depth; a pattern containing '/' (e.g. 'src/**/*.yml') "
                + "matches against the path relative to the workspace root. Set type='dir' to find "
                + "directories (e.g. pattern 'hugin') or type='any' for both. Returns matching paths "
                + "(directories end with '/'), one per line. Common build/VCS directories (.git, target, "
                + "node_modules, …) are skipped.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "pattern", Map.of(
                                "type", "string",
                                "description", "Glob to match, e.g. '*.java', 'pom.xml', 'hugin' or 'src/**/*.yml'."),
                        "path", Map.of(
                                "type", "string",
                                "description", "Directory to search under, relative to the workspace root. Defaults to '.'."),
                        "type", Map.of(
                                "type", "string",
                                "description", "Restrict results to 'file', 'dir', or 'any'. Defaults to 'file'."),
                        "max_results", Map.of(
                                "type", "integer",
                                "description", "Maximum paths to return. Defaults to " + DEFAULT_MAX_RESULTS + ".")),
                "required", List.of("pattern"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws IOException {
        String pattern = requiredString(arguments, "pattern");
        String requested = optionalString(arguments, "path", ".");
        String typeFilter = optionalString(arguments, "type", "file").toLowerCase();
        int requestedMax = Math.min(optionalInt(arguments, "max_results", DEFAULT_MAX_RESULTS), HARD_MAX_RESULTS);
        int maxResults = requestedMax <= 0 ? DEFAULT_MAX_RESULTS : requestedMax;

        Workspace ws = ctx.workspace();
        Path base = ws.resolve(requested);
        if (!Files.exists(base)) {
            return "Error: path does not exist: " + requested;
        }
        if (!Files.isDirectory(base)) {
            return "Error: path is not a directory: " + requested;
        }

        boolean wantFiles = !typeFilter.equals("dir");
        boolean wantDirs = typeFilter.equals("dir") || typeFilter.equals("any");

        // A bare name glob ('*.java') matches each entry's name at any depth; a path glob
        // ('src/**/*.yml') matches the path relative to the workspace root.
        boolean matchByName = !pattern.contains("/");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        List<String> results = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(p -> !p.equals(base))
                    .filter(p -> {
                        boolean dir = Files.isDirectory(p);
                        return dir ? wantDirs : (wantFiles && Files.isRegularFile(p));
                    })
                    .filter(p -> isNotSkipped(base, p))
                    .forEach(p -> {
                        if (results.size() >= maxResults + 1) {
                            return;
                        }
                        Path candidate = matchByName ? p.getFileName() : Path.of(ws.relativize(p));
                        if (candidate != null && matcher.matches(candidate)) {
                            results.add(Files.isDirectory(p) ? ws.relativize(p) + "/" : ws.relativize(p));
                        }
                    });
        } catch (UncheckedIOException ignored) {
            // best-effort walk; unreadable entries are skipped
        }

        if (results.isEmpty()) {
            return "No files found.";
        }
        results.sort(String::compareTo);
        boolean truncated = results.size() > maxResults;
        List<String> shown = truncated ? results.subList(0, maxResults) : results;
        String body = String.join("\n", shown);
        return truncated ? body + "\n... [truncated at " + maxResults + " matches]" : body;
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
