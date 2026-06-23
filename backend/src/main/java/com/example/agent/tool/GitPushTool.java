package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pushes the current (or named) branch to the {@code origin} remote. */
@Component
public class GitPushTool extends AbstractGitTool {

    public GitPushTool(GitCommandRunner git, Workspace defaultWorkspace) {
        super(git, defaultWorkspace);
    }

    @Override
    public String name() {
        return "git_push";
    }

    @Override
    public String description() {
        return "Push a branch to the origin remote so it can be opened as a pull request. By default "
                + "pushes the current branch and sets its upstream. Optionally specify a branch name. "
                + "Runs from the repository root.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "branch", Map.of(
                                "type", "string",
                                "description", "Branch to push. Defaults to the current branch (HEAD)."),
                        "set_upstream", Map.of(
                                "type", "boolean",
                                "description", "Set the pushed branch's upstream to origin (default true).")));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("push");
        if (optionalBoolean(arguments, "set_upstream", true)) {
            args.add("-u");
        }
        args.add("origin");
        args.add(optionalString(arguments, "branch", "HEAD"));
        return runAndRender(ctx, args);
    }
}
