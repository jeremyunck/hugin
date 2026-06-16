package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Resourceful "locate" tool: finds files <em>and</em> directories by name, partial name, or path
 * fragment anywhere under the workspace, even when the exact path the user gave does not exist.
 *
 * <p>This complements {@code find_files} (glob match on file names only) and {@code list_files}
 * (one directory) by being forgiving: given something like {@code /code/hugin/hugin} it matches on
 * the trailing path segments and the basename, so a directory simply named {@code hugin} is found
 * without the caller knowing its real location. Matching is case-insensitive substring on both the
 * entry name and its workspace-relative path; results where the name matches exactly (or the path
 * ends with the query) are ranked first.
 */
@Component
public class FindPathTool implements LocalTool {

    private static final int DEFAULT_MAX_RESULTS = 100;
    private static final int HARD_MAX_RESULTS = 2000;

    private final Workspace workspace;

    public FindPathTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public boolean requiresWorkspace() {
        return true;
    }

    @Override
    public String name() {
        return "find_path";
    }

    @Override
    public String description() {
        return "Locate a file or directory by name, partial name, or path fragment anywhere under "
                + "the workspace — use this when you know roughly what something is called but not "
                + "where it lives, or when an exact path was not found. Matching is case-insensitive "
                + "and forgiving: a query like '/code/hugin/hugin' matches a directory named 'hugin' "
                + "by its basename and trailing path segments. Returns matching paths (directories "
                + "end with '/'), best matches first. Common build/VCS directories are skipped.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of(
                                "type", "string",
                                "description", "File or directory name, partial name, or path fragment to "
                                        + "locate, e.g. 'hugin', 'AgentService.java' or '/code/hugin/hugin'."),
                        "path", Map.of(
                                "type", "string",
                                "description", "Directory to search under, relative to the workspace root. Defaults to '.'."),
                        "type", Map.of(
                                "type", "string",
                                "description", "Restrict results to 'file', 'dir', or 'any'. Defaults to 'any'."),
                        "max_results", Map.of(
                                "type", "integer",
                                "description", "Maximum paths to return. Defaults to " + DEFAULT_MAX_RESULTS + ".")),
                "required", List.of("name"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws IOException {
        String query = requiredString(arguments, "name").strip();
        String requested = optionalString(arguments, "path", ".");
        String typeFilter = optionalString(arguments, "type", "any").toLowerCase();
        int requestedMax = Math.min(optionalInt(arguments, "max_results", DEFAULT_MAX_RESULTS), HARD_MAX_RESULTS);
        int maxResults = requestedMax <= 0 ? DEFAULT_MAX_RESULTS : requestedMax;

        Workspace ws = ctx.workspace();
        Path base;
        try {
            base = ws.resolve(requested);
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        if (!Files.exists(base)) {
            return "Error: path does not exist: " + requested;
        }
        if (!Files.isDirectory(base)) {
            return "Error: path is not a directory: " + requested;
        }

        // Normalise the query into a basename (last segment) and a slash-style path fragment so we
        // can match both "hugin" against entry names and "code/hugin/hugin" against relative paths.
        String normalized = query.replace('\\', '/').replaceAll("^/+", "").replaceAll("/+$", "");
        String basename = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1)
                : normalized;
        String basenameLower = basename.toLowerCase();
        String pathFragmentLower = normalized.toLowerCase();
        if (basenameLower.isEmpty()) {
            return "Error: 'name' must contain at least one non-slash character.";
        }

        boolean wantFiles = !typeFilter.equals("dir");
        boolean wantDirs = !typeFilter.equals("file");

        List<Match> matches = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(p -> !p.equals(base))
                    .filter(p -> isNotSkipped(base, p))
                    .forEach(p -> {
                        if (matches.size() >= HARD_MAX_RESULTS) {
                            return;
                        }
                        boolean dir = Files.isDirectory(p);
                        if (dir ? !wantDirs : !wantFiles) {
                            return;
                        }
                        Path nameOnly = p.getFileName();
                        if (nameOnly == null) {
                            return;
                        }
                        String entryName = nameOnly.toString().toLowerCase();
                        String relLower = ws.relativize(p).replace('\\', '/').toLowerCase();
                        boolean nameMatch = entryName.contains(basenameLower);
                        boolean pathMatch = relLower.contains(pathFragmentLower);
                        if (!nameMatch && !pathMatch) {
                            return;
                        }
                        boolean strong = entryName.equals(basenameLower)
                                || relLower.equals(pathFragmentLower)
                                || relLower.endsWith("/" + pathFragmentLower);
                        matches.add(new Match(ws.relativize(p), dir, strong ? 0 : 1));
                    });
        } catch (UncheckedIOException ignored) {
            // best-effort walk; unreadable entries are skipped
        }

        if (matches.isEmpty()) {
            return "No files or directories matching '" + query + "' were found under " + requested + ".";
        }

        matches.sort(Comparator.comparingInt(Match::rank).thenComparing(Match::path));
        boolean truncated = matches.size() > maxResults;
        List<Match> shown = truncated ? matches.subList(0, maxResults) : matches;
        StringBuilder sb = new StringBuilder();
        for (Match m : shown) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(m.dir() ? m.path() + "/" : m.path());
        }
        if (truncated) {
            sb.append("\n... [truncated at ").append(maxResults).append(" matches]");
        }
        return sb.toString();
    }

    private static boolean isNotSkipped(Path base, Path path) {
        for (Path part : base.relativize(path)) {
            if (Workspace.IGNORED_DIRECTORIES.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private record Match(String path, boolean dir, int rank) {}
}
