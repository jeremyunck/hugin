package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Applies a targeted string replacement to a file, for surgical code edits. */
@Component
public class EditFileTool implements LocalTool {

    private final Workspace workspace;

    public EditFileTool(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Replace an exact occurrence of old_string with new_string in a file. "
                + "old_string must match exactly (including whitespace) and, unless replace_all "
                + "is true, must appear exactly once. Provide enough surrounding context to make "
                + "old_string unique. Set new_string to an empty string to delete the matched text.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of(
                                "type", "string",
                                "description", "File path, relative to the workspace root."),
                        "old_string", Map.of(
                                "type", "string",
                                "description", "Exact text to find and replace."),
                        "new_string", Map.of(
                                "type", "string",
                                "description", "Replacement text (may be empty to delete)."),
                        "replace_all", Map.of(
                                "type", "boolean",
                                "description", "Replace every occurrence instead of requiring a unique match.")),
                "required", List.of("path", "old_string", "new_string"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException {
        String requested = requiredString(arguments, "path");
        String oldString = presentString(arguments, "old_string");
        String newString = presentString(arguments, "new_string");
        boolean replaceAll = optionalBoolean(arguments, "replace_all", false);

        if (oldString.isEmpty()) {
            return "Error: old_string must not be empty.";
        }
        if (oldString.equals(newString)) {
            return "Error: old_string and new_string are identical; nothing to change.";
        }

        Path file = workspace.resolve(requested);
        if (!Files.exists(file)) {
            return "Error: file does not exist: " + requested;
        }
        if (Files.isDirectory(file)) {
            return "Error: path is a directory, not a file: " + requested;
        }

        String content = Files.readString(file);
        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            return "Error: old_string was not found in " + requested + ".";
        }
        if (!replaceAll && occurrences > 1) {
            return "Error: old_string appears " + occurrences + " times in " + requested
                    + ". Provide more surrounding context to make it unique, or set replace_all=true.";
        }

        String updated = replaceAll
                ? content.replace(oldString, newString)
                : replaceFirst(content, oldString, newString);
        Files.writeString(file, updated);

        int replaced = replaceAll ? occurrences : 1;
        return "Edited " + workspace.relativize(file) + " (" + replaced
                + (replaced == 1 ? " replacement)." : " replacements).");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        int index;
        while ((index = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from = index + needle.length();
        }
        return count;
    }

    private static String replaceFirst(String content, String oldString, String newString) {
        int index = content.indexOf(oldString);
        return content.substring(0, index) + newString + content.substring(index + oldString.length());
    }
}
