package com.example.agent;

import java.nio.file.Path;

/**
 * SPI for opening a pull request after a cloud-agent run.
 *
 * <p>Implementations (e.g. a GitHub REST API client in {@code mcp-integration}) are discovered
 * as Spring beans and wired into {@link CloudAgentService}. When no implementation is present,
 * the agent finishes without opening a PR.
 */
public interface PullRequestPublisher {

    /**
     * Commits any uncommitted changes in {@code workingTree}, pushes the branch to the remote,
     * and opens a pull request targeting {@code baseBranch}.
     *
     * @param workingTree    the cloned repository's working tree
     * @param repoUrl        the remote repository URL (used for push/POST)
     * @param branch         the source branch name
     * @param baseBranch     the target branch for the PR (e.g. "main")
     * @param title          PR title (typically based on the agent's task)
     * @return the URL of the opened pull request
     * @throws RuntimeException on git or API failure (the message is surfaced to the caller)
     */
    String publish(Path workingTree, String repoUrl, String branch, String baseBranch, String title);
}
