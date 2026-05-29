package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates workspace-relative paths against a configurable set of glob deny patterns.
 *
 * <p>Patterns follow Java glob syntax and are matched against the path of the target file
 * relative to the workspace root. Example: {@code secrets/**} blocks every file under the
 * {@code secrets/} directory; {@code *.key} blocks key files at the workspace root.
 *
 * <p>Patterns beginning with "**&#47;" are also compiled without that prefix so they match
 * files at the workspace root as well as nested paths.
 */
@Component
public class PathDenyList {

    private final List<PathMatcher> matchers;

    public PathDenyList(LocalToolProperties properties) {
        FileSystem fs = FileSystems.getDefault();
        List<PathMatcher> compiled = new ArrayList<>();
        for (String pattern : properties.denyList()) {
            if (pattern == null || pattern.isBlank()) {
                continue;
            }
            compiled.add(fs.getPathMatcher("glob:" + pattern));
            // Also match root-level files: "**/.env" won't match ".env" in Java's PathMatcher
            // because the separator is required, so we add the suffix pattern separately.
            if (pattern.startsWith("**/")) {
                compiled.add(fs.getPathMatcher("glob:" + pattern.substring(3)));
            }
        }
        this.matchers = List.copyOf(compiled);
    }

    /**
     * Returns {@code true} when {@code workspaceRelativePath} matches any deny pattern.
     * An empty deny list never blocks access.
     */
    public boolean isDenied(String workspaceRelativePath) {
        if (matchers.isEmpty()) {
            return false;
        }
        Path rel = Path.of(workspaceRelativePath);
        return matchers.stream().anyMatch(m -> m.matches(rel));
    }
}
