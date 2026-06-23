package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Creates and checks out a new branch in the repository. */
@Component
public class GitCreateBranchTool extends AbstractGitTool {

    public GitCreateBranchTool(GitCommandRunner git, Workspace defaultWorkspace) {
        super(git, defaultWorkspace);
    }

    @Override
    public String name() {
        return "git_create_branch";
    }

    @Override
    public String description() {
        return "Create a new git branch and check it out (git checkout -b). Use this to start a "
                + "feature branch before committing changes. Runs from the repository root.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of(
                                "type", "string",
                                "description", "Name of the branch to create and switch to.")),
                "required", List.of("name"));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        String name = requiredString(arguments, "name");
        return runAndRender(ctx, List.of("checkout", "-b", name));
    }
}
