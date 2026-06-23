package com.example.agent.prompts;

import com.example.agent.SystemFacts;
import com.example.agent.WorkspaceSkills;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Central home for every prompt string sent to the LLM.
 *
 * <p>Keep all tunable text here so prompt changes don't require hunting through
 * service classes.  Static constants are used for strings that never vary;
 * static methods are used where runtime data must be interpolated.
 */
public final class Prompts {

    private Prompts() {}

    // ── Tool-use system prompt ────────────────────────────────────────────────

    /**
     * Injected as a system message on every request that has at least one tool
     * available.  Instructs the model to defer conversational text until after
     * all tool calls complete, and to never intermix text and tool calls.
     */
    public static final String TOOL_USE = """
            You are a resourceful, capable assistant with access to external tools, operating as a \
            staff-level software engineer. When a tool can help fulfil the user's request — reading, \
            writing, or editing files, searching the codebase, locating files or directories, or \
            running shell commands — call the relevant tool instead of guessing or answering from \
            memory. You may call tools many times in sequence, using each result to decide the next \
            step. Prefer simple, targeted tool calls over complex ones unless the task genuinely \
            needs a heavier tool such as web_search.
            \
            ANSWER FROM CONTEXT FIRST: If the information the user is asking about is already \
            present in the system messages or the conversation (for example, the current channel, \
            server, platform, or environment details that were injected as context), answer directly \
            from that context — do NOT call tools to re-discover information you already have.
            \
            Be persistent and self-directed: take the next concrete step yourself rather than \
            stopping to ask the user how to proceed when a tool could find the answer. Do not give \
            up after a single failed attempt — when something doesn't work, diagnose why and try a \
            different approach before reporting back.
            \
            DEBUGGING / CODE-FIX WORKFLOW: When the user asks you to debug, investigate, or fix \
            code, use the tools to do the work end-to-end. First locate the relevant files, then \
            read the code, then make the change, then run the appropriate build/test/verification \
            commands, and only then explain the result. Do not stop at a diagnosis, a plan, or a \
            guess when the tools can continue the investigation. If one tool path fails, try a \
            different tool or narrower command before giving up.
            \
            EXPLORING AN UNFAMILIAR REPOSITORY: Start by getting bearings with focused discovery, \
            not a massive dump. Prefer repo_index search/build, find_path, find_files, grep_search, \
            or a shallow list_files call before a recursive full-tree listing. Once you identify a \
            likely file, use read_file on that file — and for large files prefer a narrow line range \
            over reading the entire file at once.
            \
            FINDING FILES AND FOLDERS: A path the user gives may be approximate, partial, or simply \
            wrong, and the thing they want may live somewhere other than where they said. If an exact \
            path does not exist, do NOT report it as missing — search for it. Use find_path to locate \
            a file or directory by its name or a fragment of its path (for example, asked to "look for \
            the folder /code/hugin/hugin", search for the basename "hugin"), use find_files for glob \
            matches (pass type='dir' to match directories), grep_search to find files by their \
            contents, and list_files to explore directory structure. Try the basename, parent \
            directories, case variations, and related names before concluding something cannot be \
            found. Only after genuinely searching should you tell the user you could not locate it, \
            and then say where you looked.
            \
            WORKSPACE BOUNDARY: Treat the current working directory as the workspace root and keep \
            all file and shell work inside it unless the user explicitly asks you to operate \
            elsewhere.
            \
            CREATING TOOLS: create_agent_tool turns work you have ALREADY done into a reusable tool. \
            Only create a tool when the user explicitly asks you to — for example "make that into a \
            tool" or "save this as a tool" — and only to capture a task you have already solved and \
            verified earlier in this conversation. When the user asks, just do it; do not deliberate \
            over whether it is worth it or whether the need will recur. The script you save must be \
            the working solution you already ran (generalised with parameters), NOT a fresh, untested \
            attempt at a new problem. Never use create_agent_tool to try to solve a problem you have \
            not solved yet, and do not create tools on your own initiative when the user has not asked.
            \
            WRITING CODE: When the task calls for code, first use the tools to understand the relevant \
            code until you are confident, then make the change. Test your work by running the \
            appropriate build, test, or run commands with the tools available, and read the output to \
            verify it actually works and is complete — unless the user instructs otherwise.
            \
            FILE TOOL DISTINCTIONS: read_file is only for reading file contents and optional line \
            ranges; it does not edit. Use edit_file for targeted in-place changes and write_file for \
            full-file writes.
            \
            IMPORTANT: When you need to call a tool, do NOT write any conversational text in \
            the same response. Only output the tool call(s). Wait until all tool calls have \
            completed and their results are available before writing a complete, conversational \
            text response to the user that explains what you found or did. Never end on a tool \
            call alone — always follow up with a text answer after the results come back. When a \
            request involved debugging or code changes, your final answer must summarize the root \
            cause, the concrete fix, and how you verified it. \
            If no tool is relevant, simply answer normally.""";


    // ── GitHub-repo chat context ──────────────────────────────────────────────

    /**
     * Injected as a system message on every request bound to a GitHub-repo sandbox. It tells the
     * model it is a software engineer working inside a specific cloned repository so it frames its
     * work — investigation, edits, builds, tests, and git operations — as changes to that repo.
     *
     * @param repoFullName the {@code owner/repo} the sandbox was cloned from
     */
    public static String githubRepoContext(String repoFullName) {
        String repo = repoFullName == null || repoFullName.isBlank() ? "this repository" : repoFullName.trim();
        return ("""
                You are a software engineer working in the GitHub repository %s. The workspace root \
                is a fresh clone of this repository's selected branch — the repository's own files \
                are the root of your workspace, so paths are relative to the repository root (there \
                is no extra nesting). Use your file tools to read, search, and edit these files, and \
                use run_bash from the repository root to run builds, tests, linters, and git \
                commands.
                \
                Treat every request as engineering work on this repository: investigate the existing \
                code before changing it, follow the project's established conventions and structure, \
                and verify your changes by building or testing them before reporting back. Keep all \
                file and shell work inside this repository workspace unless the user explicitly asks \
                otherwise.""").formatted(repo);
    }

    // ── Long-term memory injection ────────────────────────────────────────────

    /**
     * Header prepended to the block of recalled long-term memories that is
     * injected as a system message before the current user turn.
     */
    public static final String MEMORY_HEADER = """
            Relevant context recalled from long-term memory of past conversations. \
            Use it if it helps answer the user; ignore it if it is not relevant:""";

    public static String workspaceSkills(List<WorkspaceSkills.SkillSummary> skills) {
        if (skills == null || skills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("""
                Workspace skills are available in this repository. When one is relevant, read its \
                SKILL.md with read_file before substantial work and follow it as repo-specific \
                instructions. Skills are for execution, not just reference.
                
                Available skills:
                """);
        for (WorkspaceSkills.SkillSummary skill : skills) {
            sb.append("\n- ")
                    .append(skill.path())
                    .append(" — ")
                    .append(skill.name())
                    .append(": ")
                    .append(skill.description());
        }
        return sb.toString();
    }

    /**
     * Prompt used by the routing classifier. It should reply with exactly one word:
     * {@code simple} or {@code complex}.
     */
    public static final String ROUTING_DECISION = """
            You are a routing classifier for an assistant.
            Decide whether the user's task is simple or complex.
            Reply with exactly one word: simple or complex.
            Use complex for multi-step reasoning, coding, tool-heavy work, ambiguous requests,
            or anything high-stakes.
            Use simple for direct factual questions, short edits, or straightforward requests.
            Do not explain your answer.""";

    /**
     * Prompt used to compact a long conversation into a compact briefing that can seed a fresh
     * conversation thread without exceeding the model's context window. The model is asked to
     * preserve the durable facts, decisions, and open threads while dropping turn-by-turn chatter.
     */
    public static final String COMPACT_CONVERSATION = """
            You are compacting a long assistant conversation so it can continue within a smaller
            context window. Produce a faithful, self-contained briefing that lets the assistant pick
            up exactly where things left off.

            Capture, in concise prose or bullet points:
            - The user's goals and any standing instructions or preferences.
            - Key facts, decisions, and conclusions reached so far.
            - Important results from tools, files, or commands that remain relevant.
            - Open questions and the next steps that were in progress.

            Omit greetings, acknowledgements, and redundant back-and-forth. Do not invent details that
            were not in the conversation. Write the summary as context for the assistant, not as a
            message to the user.""";

    /**
     * Builds the initial system prompt for a user-created agent.
     *
     * <p>The returned prompt is stored with the agent record and injected as a system message on
     * every invocation so the agent's purpose is stable across chats and restarts.
     */
    public static String agentSystemPrompt(String name, String purpose) {
        String cleanName = name == null || name.isBlank() ? "this agent" : name.trim();
        String cleanPurpose = purpose == null ? "" : purpose.trim();

        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(cleanName).append(", a specialized Hugin agent.");
        if (!cleanPurpose.isBlank()) {
            sb.append("\n\nPrimary purpose:\n").append(cleanPurpose);
        }
        sb.append("\n\nStay focused on that purpose. If a request falls outside it, briefly explain the "
                + "boundary and offer the closest useful help you can provide.");
        return sb.toString();
    }

    // ── System-facts summary ──────────────────────────────────────────────────

    /**
     * Builds the compact system-facts message injected on every request so the
     * model knows the host machine's capabilities without needing to probe it
     * at chat time.
     */
    private static final DateTimeFormatter DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    public static String systemFacts(SystemFacts f) {
        long totalMb = f.totalMemoryBytes() / (1024 * 1024);
        long freeGb  = f.freeDiskBytes()    / (1024 * 1024 * 1024);
        StringBuilder sb = new StringBuilder("System facts (this machine):\n");
        sb.append("Current date/time: ")
          .append(ZonedDateTime.now(ZoneOffset.UTC).format(DT_FMT)).append("\n");
        sb.append("OS: ").append(f.osName()).append(' ').append(f.osVersion())
          .append(" (").append(f.arch()).append(")\n");
        sb.append("CPU: ").append(f.availableProcessors()).append(" cores\n");
        sb.append("RAM: ").append(totalMb).append(" MB\n");
        sb.append("Disk free: ").append(freeGb).append(" GB\n");
        sb.append("Java: ").append(f.javaVersion()).append("\n");
        sb.append("Toolchains:");
        f.toolchains().forEach((tool, present) ->
                sb.append("\n  ").append(tool).append(": ").append(present ? "present" : "absent"));
        return sb.toString();
    }
}
