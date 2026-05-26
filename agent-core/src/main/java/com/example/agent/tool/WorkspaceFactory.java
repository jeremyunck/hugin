package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Creates {@link Workspace} instances rooted at arbitrary directories for cloud agents. */
@Component
public class WorkspaceFactory {

    public Workspace create(Path root) {
        return new Workspace(root);
    }
}
