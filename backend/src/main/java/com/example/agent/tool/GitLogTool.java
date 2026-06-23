package com.example.agent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Shows recent commit history of the repository in one-line form. */
@Component
public class GitLogTool extends AbstractGitTool {

    public GitLogTool(GitCommandRunner git, Workspace defaultWorkspace) {
        super(git, defaultWorkspace);
    }

    @Override
    public String name() {
        return "git_log";
    }

    @Override
    public String description() {
        return "Show recent git commit history (one line per commit). Defaults to the 20 most recent "
                + "commits; pass count to change. Runs from the repository root.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "count", Map.of(
                                "type", "integer",
                                "description", "Number of commits to show (default 20).")));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        int count = optionalInt(arguments, "count", 20);
        if (count <= 0) {
            count = 20;
        }
        return runAndRender(ctx, List.of("log", "--oneline", "-n", Integer.toString(count)));
    }
}
