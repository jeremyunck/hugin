package com.example.agent;

import com.example.agent.model.ProvisionedRepo;

import java.nio.file.Path;

/**
 * SPI for cloning a GitHub repository and preparing a working branch for a cloud agent.
 *
 * <p>Implementations (e.g. git-CLI in {@code mcp-integration}) are discovered as Spring beans
 * and wired into {@link CloudAgentService}. When no implementation is present, cloud-agent
 * creation is rejected with a clear error.
 */
public interface RepositoryProvisioner {

    /**
     * Clones {@code repoUrl} into a new subdirectory of {@code agentDir}, checks out
     * {@code sourceBranch} (or the default branch when blank/null), then creates and checks out
     * {@code newBranchName}.
     *
     * @return metadata describing the provisioned repository (working-tree path, branch, repo name).
     * @throws RuntimeException on clone/checkout failure (the message is surfaced to the caller).
     */
    ProvisionedRepo provision(String repoUrl, Path agentDir, String sourceBranch, String newBranchName);
}
