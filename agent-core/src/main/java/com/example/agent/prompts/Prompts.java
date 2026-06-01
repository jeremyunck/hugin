package com.example.agent.prompts;

import com.example.agent.SystemFacts;

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
            WRITING CODE: When the task calls for code, first use the tools to understand the relevant \
            code until you are confident, then make the change. Test your work by running the \
            appropriate build, test, or run commands with the tools available, and read the output to \
            verify it actually works and is complete — unless the user instructs otherwise.
            \
            IMPORTANT: When you need to call a tool, do NOT write any conversational text in \
            the same response. Only output the tool call(s). Wait until all tool calls have \
            completed and their results are available before writing a complete, conversational \
            text response to the user that explains what you found or did. Never end on a tool \
            call alone — always follow up with a text answer after the results come back. \
            If no tool is relevant, simply answer normally.
            \
            ALWAYS WRITE A FINAL ANSWER: Your final reply to the user must be in the normal \
            message content. Do not leave the message empty and do not put your answer only in \
            a hidden reasoning/thinking channel — the user only sees the message content. Once \
            you have enough information, stop calling tools and write the answer.""";


    // ── Long-term memory injection ────────────────────────────────────────────

    /**
     * Header prepended to the block of recalled long-term memories that is
     * injected as a system message before the current user turn.
     */
    public static final String MEMORY_HEADER = """
            Relevant context recalled from long-term memory of past conversations. \
            Use it if it helps answer the user; ignore it if it is not relevant:""";

    // ── System-facts summary ──────────────────────────────────────────────────

    /**
     * Builds the compact system-facts message injected on every request so the
     * model knows the host machine's capabilities without needing to probe it
     * at chat time.
     */
    public static String systemFacts(SystemFacts f) {
        long totalMb = f.totalMemoryBytes() / (1024 * 1024);
        long freeGb  = f.freeDiskBytes()    / (1024 * 1024 * 1024);
        StringBuilder sb = new StringBuilder("System facts (this machine):\n");
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
