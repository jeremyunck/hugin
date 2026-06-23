package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Stages changes and records a commit in the repository. */
@Component
public class GitCommitTool extends AbstractGitTool {

    public GitCommitTool(GitCommandRunner git, Workspace defaultWorkspace) {
        super(git, defaultWorkspace);
    }

    @Override
    public String name() {
        return "git_commit";
    }

    @Override
    public String description() {
        return "Create a git commit in the repository with the given message. By default stages all "
                + "changes (tracked and untracked) before committing; set add_all=false to commit only "
                + "what is already staged. Runs from the repository root.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of(
                                "type", "string",
                                "description", "The commit message."),
                        "add_all", Map.of(
                                "type", "boolean",
                                "description", "Stage all changes before committing (default true). "
                                        + "Set false to commit only already-staged changes.")),
                "required", List.of("message"));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        String message = requiredString(arguments, "message");

        if (optionalBoolean(arguments, "add_all", true)) {
            GitCommandRunner.Result staged = git.run(ctx, List.of("add", "-A"));
            if (!staged.ok()) {
                return "Failed to stage changes before commit.\n"
                        + "exit code: " + staged.exitCode()
                        + (staged.output().isBlank() ? "" : "\n" + staged.output());
            }
        }

        return runAndRender(ctx, List.of("commit", "-m", message));
    }
}
