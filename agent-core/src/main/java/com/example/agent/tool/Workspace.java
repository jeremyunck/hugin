package com.example.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves and confines file paths used by the local tools to a single workspace root.
 *
 * <p>Paths may be given relative to the root or as absolute paths, but the resolved path —
 * after symlinks are followed — must stay inside the root. This prevents both {@code ../}
 * traversal and escapes via symbolic links pointing outside the workspace. The root also
 * serves as the working directory for shell commands.
 */
@Component
public class Workspace {

    private static final Logger log = LoggerFactory.getLogger(Workspace.class);

    private final Path root;

    public Workspace(LocalToolProperties properties) {
        Path configured = Path.of(properties.workspaceRoot()).toAbsolutePath().normalize();
        this.root = realPathQuietly(configured);
        log.info("Local tool workspace root: {}", root);
    }

    public Path root() {
        return root;
    }

    /** Resolves a user-supplied path against the root, rejecting anything that escapes it. */
    public Path resolve(String path) {
        Path candidate = Path.of(path);
        Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
        if (!realPath(resolved).startsWith(root)) {
            throw new IllegalArgumentException(
                    "Path '" + path + "' resolves outside the workspace root " + root);
        }
        return resolved;
    }

    /**
     * Returns the real (symlink-resolved) path of {@code path}. Because the target may not exist
     * yet (e.g. a file about to be written), this resolves symlinks on the deepest existing
     * ancestor and re-appends the remaining, not-yet-created segments.
     */
    private static Path realPath(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return path;
        }
        Path realExisting = realPathQuietly(existing);
        return realExisting.resolve(existing.relativize(path)).normalize();
    }

    private static Path realPathQuietly(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    /** Renders {@code path} relative to the root for display in tool output. */
    public String relativize(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root) ? root.relativize(normalized).toString() : normalized.toString();
    }
}
