package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Creates a new file or overwrites an existing one within the workspace. */
@Component
public class WriteFileTool implements LocalTool {

    private final Workspace workspace;

    public WriteFileTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Create a new file or overwrite an existing file with the given content. "
                + "Parent directories are created as needed. Use edit_file for targeted "
                + "changes to large files.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "File path, relative to the workspace root."),
                        "content", Map.of(
                                "type", "string",
                                "description", "Full content to write to the file.")),
                "required", List.of("path", "content"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws IOException {
        String requested = requiredString(arguments, "path");
        String content = presentString(arguments, "content");
        Workspace ws = ctx.workspace();
        Path file = ws.resolve(requested);

        if (Files.isDirectory(file)) {
            return "Error: path is a directory, not a file: " + requested;
        }

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content);
        return "Wrote " + content.length() + " characters to " + ws.relativize(file);
    }
}
