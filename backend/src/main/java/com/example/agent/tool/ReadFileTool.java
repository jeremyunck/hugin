package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Reads the contents of a text file within the workspace. */
@Component
public class ReadFileTool implements LocalTool {

    private final Workspace workspace;
    private final int maxChars;
    private final PathDenyList denyList;

    public ReadFileTool(Workspace workspace, LocalToolProperties properties, PathDenyList denyList) {
        this.workspace = workspace;
        this.maxChars = properties.maxOutputChars();
        this.denyList = denyList;
    }

    @Override
    public boolean requiresWorkspace() {
        return true;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read and return the contents of a UTF-8 text file within the workspace. "
                + "Supports optional line ranges via start_line and line_count. Large files are truncated.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "File path, relative to the workspace root."),
                        "start_line", Map.of(
                                "type", "integer",
                                "description", "Optional 1-based starting line number for a narrower read."),
                        "line_count", Map.of(
                                "type", "integer",
                                "description", "Optional number of lines to read when using start_line. Defaults to 200 for ranged reads.")),
                "required", List.of("path"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws IOException {
        String requested = requiredString(arguments, "path");
        if (arguments.containsKey("old_string") || arguments.containsKey("new_string")
                || arguments.containsKey("replace_all")) {
            return "Error: read_file only reads files. Use edit_file for old_string/new_string replacements.";
        }
        Workspace ws = ctx.workspace();
        Path file = ws.resolve(requested);
        String relative = ws.relativize(file);
        boolean hasStartLine = arguments.containsKey("start_line");
        boolean hasLineCount = arguments.containsKey("line_count");
        int startLine = Math.max(1, optionalInt(arguments, "start_line", 1));
        int lineCount = Math.max(1, optionalInt(arguments, "line_count", 200));

        if (denyList.isDenied(relative)) {
            return "Error: access to '" + requested + "' is denied by configuration.";
        }

        if (!Files.exists(file)) {
            return "Error: file does not exist: " + requested;
        }
        if (Files.isDirectory(file)) {
            return listDirectory(file);
        }

        if (hasStartLine || hasLineCount) {
            return readLineRange(file, startLine, lineCount);
        }

        String content = Files.readString(file);
        if (content.isEmpty()) {
            return "(file is empty)";
        }
        if (content.length() > maxChars) {
            return content.substring(0, maxChars)
                    + "\n... [truncated " + (content.length() - maxChars)
                    + " characters; use start_line and line_count for a narrower slice]";
        }
        return content;
    }

    private static String readLineRange(Path file, int startLine, int lineCount) throws IOException {
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty()) {
            return "(file is empty)";
        }
        if (startLine > lines.size()) {
            return "Error: start_line " + startLine + " is beyond end of file (" + lines.size() + " lines).";
        }
        int fromIndex = startLine - 1;
        int toIndex = Math.min(lines.size(), fromIndex + lineCount);
        return String.join("\n", lines.subList(fromIndex, toIndex));
    }

    private static String listDirectory(Path dir) throws IOException {
        List<String> entries;
        try (Stream<Path> stream = Files.list(dir)) {
            entries = stream.sorted(Comparator.comparing(Path::toString))
                    .map(p -> {
                        String name = dir.relativize(p).toString();
                        return Files.isDirectory(p) ? name + "/" : name;
                    })
                    .toList();
        }
        return entries.isEmpty() ? "(empty directory)" : String.join("\n", entries);
    }
}
