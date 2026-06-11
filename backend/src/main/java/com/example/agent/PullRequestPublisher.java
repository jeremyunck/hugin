package com.example.agent;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Publishes a finalized cloud-agent branch as a GitHub pull request.
 *
 * <p>Implemented in the backend module so the agent core stays free of GitHub SDK details.
 */
public interface PullRequestPublisher {

    /**
     * Creates a pull request for the given working tree and branch.
     *
     * @param repoUrl     original repository URL provided by the caller
     * @param workingTree checked-out repository root
     * @param branch      head branch to publish
     * @param baseBranch  target branch
     * @param title       PR title
     * @param body        PR body
     * @return PR URL when a PR was opened, or empty when publication is unavailable
     */
    Optional<String> publish(String repoUrl, Path workingTree, String branch, String baseBranch,
                             String title, String body);
}
