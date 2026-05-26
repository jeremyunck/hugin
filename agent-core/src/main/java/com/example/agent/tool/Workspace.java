package com.example.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

    /** Directories skipped by recursive tools (search, listing). */
    public static final List<String> IGNORED_DIRECTORIES =
            List.of(".git", "target", "node_modules", "build", "dist", ".idea");

    private final Path root;

    /** Spring-managed default workspace, rooted at {@code agent.tools.workspace-root}. */
    @Autowired
    public Workspace(LocalToolProperties properties) {
        this(Path.of(properties.workspaceRoot()).toAbsolutePath().normalize());
        log.info("Local tool workspace root: {}", root);
    }

    /** Creates a workspace rooted at an arbitrary directory (used by {@link WorkspaceFactory}). */
    Workspace(Path root) {
        Path r = root.isAbsolute() ? root.normalize() : root.toAbsolutePath().normalize();
        this.root = realPathQuietly(r);
    }

    public Path root() {
        return root;
    }

    /** Resolves a user-supplied path against the root, rejecting anything that escapes it. */
    public Path resolve(String path) {
        Path candidate = Path.of(path);
        Path resolved = (candidate.isAbsolute() ? candidate : root.resolve(candidate)).normalize();
        Path real;
        try {
            real = realPath(resolved);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Path '" + path + "' could not be resolved within the workspace root " + root, e);
        }
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Path '" + path + "' resolves outside the workspace root " + root);
        }
        return resolved;
    }

    /**
     * Returns the real (symlink-resolved) path of {@code path}. Because the target may not exist
     * yet (e.g. a file about to be written), this resolves symlinks on the deepest existing
     * ancestor and re-appends the remaining, not-yet-created segments. Dangling symlink components
     * are rejected so an unresolvable link cannot be treated as an in-workspace path.
     */
    private static Path realPath(Path path) throws IOException {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            if (Files.isSymbolicLink(existing)) {
                throw new IOException("Dangling symbolic link in path: " + existing);
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new IOException("No existing ancestor for path: " + path);
        }
        Path realExisting = existing.toRealPath();
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
