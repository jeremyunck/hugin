package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Reads the contents of a text file within the workspace. */
@Component
public class ReadFileTool implements LocalTool {

    private final Workspace workspace;
    private final int maxChars;

    public ReadFileTool(Workspace workspace, LocalToolProperties properties) {
        this.workspace = workspace;
        this.maxChars = properties.maxOutputChars();
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Read and return the contents of a UTF-8 text file within the workspace. "
                + "Large files are truncated.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "File path, relative to the workspace root.")),
                "required", List.of("path"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException {
        String requested = requiredString(arguments, "path");
        Path file = workspace.resolve(requested);

        if (!Files.exists(file)) {
            return "Error: file does not exist: " + requested;
        }
        if (Files.isDirectory(file)) {
            return "Error: path is a directory, not a file: " + requested;
        }

        String content = Files.readString(file);
        if (content.isEmpty()) {
            return "(file is empty)";
        }
        if (content.length() > maxChars) {
            return content.substring(0, maxChars)
                    + "\n... [truncated " + (content.length() - maxChars) + " characters]";
        }
        return content;
    }
}
