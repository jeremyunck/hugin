package com.example.agent.model;

import java.nio.file.Path;

/**
 * Result returned by {@link com.example.agent.RepositoryProvisioner} after a repository has been
 * cloned and a working branch checked out.
 */
public record ProvisionedRepo(Path workingTree, String branch, String repoName) {}
