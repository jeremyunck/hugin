package com.example.agent.tool;

import com.example.agent.sandbox.FileEntry;
import com.example.agent.sandbox.FileResult;
import com.example.agent.sandbox.SandboxRuntime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared implementations of the workspace file tools (read/write/edit/list/grep/find) for an isolated
 * project chat, where the repository lives only inside a Docker container.
 *
 * <p>Every operation here runs through the {@link SandboxRuntime} for the request's sandbox — file
 * reads/writes/listings via the runtime's file API, and content/name search via {@code docker exec}
 * shell commands — so no host filesystem access ever occurs for a project chat. The individual
 * {@link LocalTool}s delegate here when {@link ToolContext#requiresContainer()} is true.
 */
final class ContainerWorkspaceTools {

    private ContainerWorkspaceTools() {}

    static String readFile(SandboxRuntime rt, String sandboxId, String path, int maxChars,
                           boolean ranged, int startLine, int lineCount) {
        FileResult result = rt.readFile(sandboxId, path);
        if (!result.exists()) {
            return "Error: file does not exist: " + path;
        }
        String content = result.content();
        if (ranged) {
            String[] lines = content.split("\n", -1);
            if (lines.length == 0 || content.isEmpty()) {
                return "(file is empty)";
            }
            if (startLine > lines.length) {
                return "Error: start_line " + startLine + " is beyond end of file (" + lines.length + " lines).";
            }
            int from = startLine - 1;
            int to = Math.min(lines.length, from + lineCount);
            return String.join("\n", List.of(lines).subList(from, to));
        }
        if (content.isEmpty()) {
            return "(file is empty)";
        }
        if (content.length() > maxChars) {
            return content.substring(0, maxChars)
                    + "\n... [truncated; use start_line and line_count for a narrower slice]";
        }
        return content;
    }

    static String writeFile(SandboxRuntime rt, String sandboxId, String path, String content) {
        rt.writeFile(sandboxId, path, content);
        return "Wrote " + content.length() + " characters to " + path;
    }

    static String editFile(SandboxRuntime rt, String sandboxId, String path,
                           String oldString, String newString, boolean replaceAll) {
        FileResult result = rt.readFile(sandboxId, path);
        if (!result.exists()) {
            return "Error: file does not exist: " + path;
        }
        String content = result.content();
        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            return "Error: old_string was not found in " + path + ".";
        }
        if (!replaceAll && occurrences > 1) {
            return "Error: old_string appears " + occurrences + " times in " + path
                    + ". Provide more surrounding context to make it unique, or set replace_all=true.";
        }
        String updated = replaceAll
                ? content.replace(oldString, newString)
                : replaceFirst(content, oldString, newString);
        rt.writeFile(sandboxId, path, updated);
        int replaced = replaceAll ? occurrences : 1;
        return "Edited " + path + " (" + replaced + (replaced == 1 ? " replacement)." : " replacements).");
    }

    static String listFiles(SandboxRuntime rt, String sandboxId, String path, boolean recursive,
                            int maxEntries, Duration timeout) {
        if (!recursive) {
            List<FileEntry> entries;
            try {
                entries = rt.listFiles(sandboxId, path);
            } catch (RuntimeException e) {
                return "Error: directory does not exist: " + path;
            }
            if (entries.isEmpty()) {
                return "(empty directory)";
            }
            List<String> rendered = new ArrayList<>();
            for (FileEntry entry : entries) {
                rendered.add(entry.directory() ? entry.name() + "/" : entry.name());
            }
            return truncate(rendered, maxEntries);
        }
        // Recursive listing: a single find inside the container, skipping the usual build/VCS dirs.
        String dir = shellPath(path);
        String command = "cd " + quote(dir) + " 2>/dev/null && find . -mindepth 1 "
                + pruneExpr() + " -printf '%y %P\\n' | sort -k2 | head -n " + (maxEntries + 1)
                + " || echo __BOUW_NOPATH__";
        SandboxRuntime.ExecResult result = exec(rt, sandboxId, command, timeout);
        if (result.output().contains("__BOUW_NOPATH__")) {
            return "Error: directory does not exist: " + path;
        }
        List<String> rendered = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            char type = trimmed.charAt(0);
            String name = trimmed.length() > 2 ? trimmed.substring(2) : "";
            rendered.add(type == 'd' ? name + "/" : name);
        }
        if (rendered.isEmpty()) {
            return "(empty directory)";
        }
        return truncate(rendered, maxEntries);
    }

    static String grep(SandboxRuntime rt, String sandboxId, String pattern, String path, String glob,
                       boolean ignoreCase, int maxResults, Duration timeout) {
        StringBuilder cmd = new StringBuilder("grep -rnIE ");
        if (ignoreCase) {
            cmd.append("-i ");
        }
        if (glob != null && !glob.isBlank()) {
            cmd.append("--include=").append(quote(glob)).append(' ');
        }
        cmd.append("-- ").append(quote(pattern)).append(' ').append(quote(shellPath(path)))
                .append(" 2>/dev/null | head -n ").append(maxResults + 1);
        SandboxRuntime.ExecResult result = exec(rt, sandboxId, cmd.toString(), timeout);
        List<String> lines = nonEmptyLines(result.output());
        if (lines.isEmpty()) {
            return "No matches found.";
        }
        return truncate(lines, maxResults, "matches");
    }

    static String findFiles(SandboxRuntime rt, String sandboxId, String pattern, String path,
                            String typeFilter, int maxResults, Duration timeout) {
        boolean wantFiles = !typeFilter.equals("dir");
        boolean wantDirs = typeFilter.equals("dir") || typeFilter.equals("any");
        StringBuilder typeExpr = new StringBuilder();
        if (wantFiles && !wantDirs) {
            typeExpr.append(" -type f");
        } else if (wantDirs && !wantFiles) {
            typeExpr.append(" -type d");
        }
        // A bare name glob matches the entry name; a path glob ('src/**/*.yml') matches the relative path.
        String matchExpr = pattern.contains("/")
                ? " -path " + quote("./" + pattern.replace("**", "*"))
                : " -name " + quote(pattern);
        String command = "cd " + quote(shellPath(path)) + " 2>/dev/null && find . -mindepth 1 "
                + pruneExpr() + typeExpr + matchExpr
                + " -printf '%y %P\\n' | sort -k2 | head -n " + (maxResults + 1)
                + " || echo __BOUW_NOPATH__";
        SandboxRuntime.ExecResult result = exec(rt, sandboxId, command, timeout);
        if (result.output().contains("__BOUW_NOPATH__")) {
            return "Error: path does not exist: " + path;
        }
        List<String> rendered = new ArrayList<>();
        for (String line : result.output().split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            char type = trimmed.charAt(0);
            String name = trimmed.length() > 2 ? trimmed.substring(2) : "";
            rendered.add(type == 'd' ? name + "/" : name);
        }
        if (rendered.isEmpty()) {
            return "No files found.";
        }
        return truncate(rendered, maxResults, "matches");
    }

    // --- helpers -------------------------------------------------------------------------------

    private static SandboxRuntime.ExecResult exec(SandboxRuntime rt, String sandboxId, String command,
                                                  Duration timeout) {
        try {
            return rt.exec(sandboxId, command, timeout);
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            return new SandboxRuntime.ExecResult(-1, "", false);
        }
    }

    /** {@code find} prune expression skipping the usual build/VCS directories. */
    private static String pruneExpr() {
        StringBuilder sb = new StringBuilder("-not \\( ");
        boolean first = true;
        for (String dir : Workspace.IGNORED_DIRECTORIES) {
            if (!first) {
                sb.append("-o ");
            }
            sb.append("-path ").append(quote("./" + dir + "/*")).append(' ');
            sb.append("-o -path ").append(quote("./" + dir)).append(' ');
            first = false;
        }
        sb.append("\\) ");
        return sb.toString();
    }

    private static String shellPath(String path) {
        return path == null || path.isBlank() ? "." : path;
    }

    private static List<String> nonEmptyLines(String output) {
        List<String> lines = new ArrayList<>();
        for (String line : output.split("\n")) {
            if (!line.strip().isEmpty()) {
                lines.add(line.stripTrailing());
            }
        }
        return lines;
    }

    private static String truncate(List<String> lines, int max) {
        return truncate(lines, max, "entries");
    }

    private static String truncate(List<String> lines, int max, String noun) {
        boolean truncated = lines.size() > max;
        List<String> shown = truncated ? lines.subList(0, max) : lines;
        String body = String.join("\n", shown);
        return truncated ? body + "\n... [truncated at " + max + " " + noun + "]" : body;
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

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
