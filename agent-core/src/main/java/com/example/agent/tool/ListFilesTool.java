package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Lists files and directories within the workspace. */
@Component
public class ListFilesTool implements LocalTool {

    private static final int MAX_ENTRIES = 1000;
    private static final List<String> SKIP_DIRS =
            List.of(".git", "target", "node_modules", "build", "dist", ".idea");

    private final Workspace workspace;

    public ListFilesTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return "List files and directories within the workspace. Directory names end with '/'. "
                + "Set recursive=true to descend into subdirectories (common build/VCS directories "
                + "such as .git, target and node_modules are skipped).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "Directory to list, relative to the workspace root. Defaults to '.'."),
                        "recursive", Map.of(
                                "type", "boolean",
                                "description", "List subdirectories recursively. Defaults to false.")),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException {
        String requested = optionalString(arguments, "path", ".");
        boolean recursive = optionalBoolean(arguments, "recursive", false);
        Path dir = workspace.resolve(requested);

        if (!Files.exists(dir)) {
            return "Error: directory does not exist: " + requested;
        }
        if (!Files.isDirectory(dir)) {
            return "Error: path is not a directory: " + requested;
        }

        List<String> entries = new ArrayList<>();
        if (recursive) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> !p.equals(dir))
                        .filter(p -> isNotSkipped(dir, p))
                        .sorted(Comparator.comparing(Path::toString))
                        .limit(MAX_ENTRIES + 1L)
                        .forEach(p -> entries.add(format(dir, p)));
            }
        } else {
            try (Stream<Path> list = Files.list(dir)) {
                list.sorted(Comparator.comparing(Path::toString))
                        .limit(MAX_ENTRIES + 1L)
                        .forEach(p -> entries.add(format(dir, p)));
            }
        }

        if (entries.isEmpty()) {
            return "(empty directory)";
        }

        boolean truncated = entries.size() > MAX_ENTRIES;
        List<String> shown = truncated ? entries.subList(0, MAX_ENTRIES) : entries;
        String body = String.join("\n", shown);
        return truncated ? body + "\n... [truncated; more than " + MAX_ENTRIES + " entries]" : body;
    }

    private static boolean isNotSkipped(Path base, Path path) {
        for (Path part : base.relativize(path)) {
            if (SKIP_DIRS.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private static String format(Path base, Path path) {
        String relative = base.relativize(path).toString();
        return Files.isDirectory(path) ? relative + "/" : relative;
    }
}
