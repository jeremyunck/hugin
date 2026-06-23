package com.example.agent.tool;

import com.example.agent.model.FileNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Backs the "Agent" chat mode, which runs the agent directly against the server's home directory
 * ({@code ~/}) instead of a per-session Docker sandbox.
 *
 * <p>Exposes the home-rooted {@link Workspace} the agent's filesystem/shell tools operate in, and a
 * bounded file tree of {@code ~/} for the UI's workspace panel. The walk mirrors the sandbox file
 * listing: it skips {@link Workspace#IGNORED_DIRECTORIES}, is depth- and width-bounded, and sorts
 * directories before files (each alphabetically).
 */
@Component
public class HomeWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(HomeWorkspaceService.class);

    private static final int MAX_TREE_DEPTH = 6;
    private static final int MAX_ENTRIES_PER_DIR = 500;

    private final Path home;
    private final WorkspaceFactory workspaceFactory;
    private volatile Workspace workspace;

    public HomeWorkspaceService(WorkspaceFactory workspaceFactory,
                                @Value("${agent.tools.home-root:${user.home}}") String homeRoot) {
        this.workspaceFactory = workspaceFactory;
        this.home = Path.of(homeRoot).toAbsolutePath().normalize();
        log.info("Agent home workspace root: {}", home);
    }

    /** The absolute {@code ~/} root the agent mode operates in. */
    public Path root() {
        return home;
    }

    /** The home-rooted workspace the agent's tools run inside; created lazily and cached. */
    public Workspace workspace() {
        Workspace existing = workspace;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (workspace == null) {
                workspace = workspaceFactory.create(home);
            }
            return workspace;
        }
    }

    /** The bounded {@code ~/} file tree (relative to home), for the UI workspace panel. */
    public List<FileNode> files() {
        return buildChildren(home, home, 0);
    }

    private List<FileNode> buildChildren(Path root, Path dir, int depth) {
        if (depth >= MAX_TREE_DEPTH || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.forEach(entries::add);
        } catch (IOException e) {
            log.debug("Could not list home directory {}: {}", dir, e.getMessage());
            return List.of();
        }
        entries.sort(Comparator
                .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                .thenComparing(p -> p.getFileName().toString().toLowerCase()));

        List<FileNode> nodes = new ArrayList<>();
        for (Path entry : entries) {
            if (nodes.size() >= MAX_ENTRIES_PER_DIR) {
                break;
            }
            String name = entry.getFileName().toString();
            String relative = root.relativize(entry).toString();
            if (Files.isDirectory(entry)) {
                if (Workspace.IGNORED_DIRECTORIES.contains(name)) {
                    continue;
                }
                nodes.add(FileNode.directory(name, relative, buildChildren(root, entry, depth + 1)));
            } else {
                long size;
                try {
                    size = Files.size(entry);
                } catch (IOException e) {
                    size = 0L;
                }
                nodes.add(FileNode.file(name, relative, size));
            }
        }
        return nodes;
    }
}
