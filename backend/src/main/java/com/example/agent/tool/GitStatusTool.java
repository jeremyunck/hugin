package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Shows the working-tree status of the repository (porcelain short form with branch info). */
@Component
public class GitStatusTool extends AbstractGitTool {

    public GitStatusTool(GitCommandRunner git, Workspace defaultWorkspace) {
        super(git, defaultWorkspace);
    }

    @Override
    public String name() {
        return "git_status";
    }

    @Override
    public String description() {
        return "Show the git working-tree status of the repository: the current branch and the "
                + "staged, unstaged, and untracked files. Runs from the repository root.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        return runAndRender(ctx, List.of("status", "--short", "--branch"));
    }
}
