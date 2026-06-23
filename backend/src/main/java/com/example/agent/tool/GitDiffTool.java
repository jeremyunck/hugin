package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shows the diff of changes in the repository, optionally staged-only or scoped to a path. */
@Component
public class GitDiffTool extends AbstractGitTool {

    public GitDiffTool(GitCommandRunner git, Workspace defaultWorkspace) {
        super(git, defaultWorkspace);
    }

    @Override
    public String name() {
        return "git_diff";
    }

    @Override
    public String description() {
        return "Show the git diff of changes in the repository. By default shows unstaged changes; "
                + "set staged=true to show what is staged for the next commit. Optionally limit to a "
                + "single path. Runs from the repository root.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "staged", Map.of(
                                "type", "boolean",
                                "description", "When true, show staged changes (git diff --staged) instead of unstaged."),
                        "path", Map.of(
                                "type", "string",
                                "description", "Optional repository-relative path to limit the diff to.")));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("diff");
        if (optionalBoolean(arguments, "staged", false)) {
            args.add("--staged");
        }
        String path = optionalString(arguments, "path", "");
        if (!path.isBlank()) {
            args.add("--");
            args.add(path);
        }
        return runAndRender(ctx, args);
    }
}
