package com.example.agent.tool;

import java.util.List;
import java.util.Map;

/**
 * Base for the built-in git tools. Each operates from the repository root via the shared
 * {@link GitCommandRunner}, so all git work stays confined to the repository folder.
 *
 * <p>Git tools require a real workspace (a cloned repository) to be useful, so they are only
 * advertised to sandbox-backed sessions — the same gate used by the file and shell tools.
 */
abstract class AbstractGitTool implements LocalTool {

    protected final GitCommandRunner git;
    private final Workspace defaultWorkspace;

    protected AbstractGitTool(GitCommandRunner git, Workspace defaultWorkspace) {
        this.git = git;
        this.defaultWorkspace = defaultWorkspace;
    }

    @Override
    public boolean requiresWorkspace() {
        return true;
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        return execute(arguments, new ToolContext(defaultWorkspace));
    }

    /** Runs git and renders its exit code and combined output, mirroring run_bash's format. */
    protected String runAndRender(ToolContext ctx, List<String> args) throws Exception {
        GitCommandRunner.Result result = git.run(ctx, args);
        if (result.timedOut()) {
            return "Error: git command timed out.\nPartial output:\n" + result.output();
        }
        String output = result.output();
        return "exit code: " + result.exitCode() + (output.isBlank() ? " (no output)" : "\n" + output);
    }
}
