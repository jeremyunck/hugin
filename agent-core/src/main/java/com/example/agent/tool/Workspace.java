package com.example.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * Resolves and confines file paths used by the local tools to a single workspace root.
 *
 * <p>Paths may be given relative to the root or as absolute paths, but the resolved,
 * normalised path must stay inside the root — this prevents {@code ../} traversal out
 * of the workspace. The root also serves as the working directory for shell commands.
 */
@Component
public class Workspace {

    private static final Logger log = LoggerFactory.getLogger(Workspace.class);

    private final Path root;

    public Workspace(LocalToolProperties properties) {
        this.root = Path.of(properties.workspaceRoot()).toAbsolutePath().normalize();
        log.info("Local tool workspace root: {}", root);
    }

    public Path root() {
        return root;
    }

    /** Resolves a user-supplied path against the root, rejecting anything that escapes it. */
    public Path resolve(String path) {
        Path candidate = Path.of(path);
        Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Path '" + path + "' resolves outside the workspace root " + root);
        }
        return resolved;
    }

    /** Renders {@code path} relative to the root for display in tool output. */
    public String relativize(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root) ? root.relativize(normalized).toString() : normalized.toString();
    }
}
