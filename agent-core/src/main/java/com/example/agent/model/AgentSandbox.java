package com.example.agent.model;

import java.nio.file.Path;

/**
 * Per-agent filesystem sandbox.
 *
 * <p>The repository checkout lives under {@code repoRoot}; {@code homeDir} and {@code tmpDir}
 * are used to isolate tool and git subprocess state for the lifetime of the agent.
 */
public record AgentSandbox(Path root, Path repoRoot, Path homeDir, Path tmpDir) {}
