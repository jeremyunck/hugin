package com.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lets the agent teach itself a new, reusable capability by writing a self-contained script and a
 * just-in-time tool manifest into the current workspace.
 *
 * <p>The motivating scenario: the agent solves a task once with ad-hoc {@code run_bash} scripting
 * (e.g. "look up a stock ticker price with yfinance"), and the user then asks it to "make that a
 * tool". The agent crystallises the working solution it already ran into a first-class
 * {@code lookup_stock_ticker} tool so future requests can call it directly instead of re-deriving the
 * script. Tool creation is user-driven: the agent saves work it has <em>already</em> done on request,
 * rather than deciding on its own initiative or using this to attempt an unsolved problem.
 *
 * <p>The generated script lives under the workspace's just-in-time tool directory
 * ({@code .bouw/jit-tools/scripts/}), which this tool keeps out of version control by writing a
 * {@code .gitignore} alongside the manifests. {@link JustInTimeToolRegistry} rescans that directory
 * on every reasoning pass, so a freshly created tool becomes callable on the next loop iteration
 * <em>without restarting the service</em>.
 *
 * <p><strong>Guardrails.</strong> New tools are a long-lived addition to the agent's surface area, so
 * this tool is deliberately conservative: it requires the model to name the already-solved task the
 * tool captures, validates the name, refuses to shadow a built-in or clobber an existing tool unless
 * {@code overwrite} is set, and caps the total number of generated tools per workspace.
 * The prompt text reinforces that the agent only creates a tool when the user asks, and only from a
 * solution it has already run — never to attempt a problem it has not solved.
 */
@Component
public class CreateAgentToolTool implements LocalTool {

    private static final Logger log = LoggerFactory.getLogger(CreateAgentToolTool.class);

    /** Tool names must be snake_case identifiers so they are valid, predictable, and collision-free. */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");
    private static final int MAX_CODE_CHARS = 200_000;

    private record Language(String command, String extension) {}

    /** Supported runtimes for a generated tool, keyed by the value the model passes in {@code language}. */
    private static final Map<String, Language> LANGUAGES = Map.of(
            "python", new Language("python3", "py"),
            "bash", new Language("bash", "sh"),
            "node", new Language("node", "js"));

    private final Workspace workspace;
    private final LocalToolProperties properties;
    private final JustInTimeToolRegistry jitTools;
    /** Lazily resolved to avoid a bean cycle: the registry's tool list includes this tool. */
    private final ObjectProvider<LocalToolRegistry> localToolRegistry;
    private final ObjectMapper objectMapper;
    private final int maxTools;

    public CreateAgentToolTool(Workspace workspace,
                               LocalToolProperties properties,
                               JustInTimeToolRegistry jitTools,
                               ObjectProvider<LocalToolRegistry> localToolRegistry,
                               ObjectMapper objectMapper,
                               @Value("${agent.tools.max-jit-tools:24}") int maxTools) {
        this.workspace = workspace;
        this.properties = properties;
        this.jitTools = jitTools;
        this.localToolRegistry = localToolRegistry;
        this.objectMapper = objectMapper;
        this.maxTools = maxTools > 0 ? maxTools : 24;
    }

    @Override
    public boolean requiresWorkspace() {
        return true;
    }

    @Override
    public String name() {
        return "create_agent_tool";
    }

    @Override
    public String description() {
        return "Save a task you have ALREADY solved in this conversation as a new, reusable tool that "
                + "you (and future requests) can call directly. It writes a self-contained script plus "
                + "a manifest into the workspace; the tool is loaded on the fly and becomes callable on "
                + "the next step — no restart needed.\n"
                + "Only call this when the user explicitly asks you to turn something you just did into "
                + "a tool (e.g. \"make that a tool\"). The 'code' must be the working solution you "
                + "already ran — for example the run_bash script you just used to fetch a stock price "
                + "with yfinance — generalised with parameters, NOT a fresh, untested attempt at a new "
                + "problem. Do not create tools on your own initiative, and never use this to try to "
                + "solve something you have not solved yet. The script receives the call arguments as a "
                + "JSON object on stdin (also in the BOUW_TOOL_ARGS_JSON env var) and should print its "
                + "result to stdout. After creating the tool, make sure its dependencies are installed "
                + "and verify it by calling it once.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of(
                "type", "string",
                "description", "snake_case name for the new tool, e.g. 'lookup_stock_ticker'. "
                        + "3-64 chars, lowercase letters/digits/underscores, must start with a letter."));
        properties.put("description", Map.of(
                "type", "string",
                "description", "Clear description shown to the model so it knows when to call the tool. "
                        + "State what it does and what each argument means."));
        properties.put("language", Map.of(
                "type", "string",
                "enum", List.of("python", "bash", "node"),
                "description", "Runtime for the script: 'python' (python3), 'bash', or 'node'."));
        properties.put("code", Map.of(
                "type", "string",
                "description", "Full source of the script. It must read its arguments as a JSON object "
                        + "from stdin and print results to stdout. Keep it self-contained and idempotent."));
        properties.put("input_schema", Map.of(
                "type", "object",
                "description", "JSON Schema for the new tool's parameters (a JSON-schema 'object' with "
                        + "'properties' and optional 'required'). Omit if the tool takes no arguments."));
        properties.put("solved_task", Map.of(
                "type", "string",
                "description", "The task you already solved in this conversation that this tool captures "
                        + "— e.g. 'fetched AAPL's price with a yfinance run_bash script'. Required, to "
                        + "keep tools grounded in work already done rather than untested new attempts."));
        properties.put("timeout_seconds", Map.of(
                "type", "integer",
                "description", "Optional per-call timeout in seconds for the generated tool."));
        properties.put("overwrite", Map.of(
                "type", "boolean",
                "description", "Set true to replace an existing tool of the same name. Defaults to false."));

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of("name", "description", "language", "code", "solved_task"));
    }

    @Override
    public String execute(Map<String, Object> arguments) throws Exception {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        String name = requiredString(arguments, "name").trim();
        String description = requiredString(arguments, "description").trim();
        String language = requiredString(arguments, "language").trim().toLowerCase(Locale.ROOT);
        String code = optionalString(arguments, "code", "");
        String solvedTask = requiredString(arguments, "solved_task").trim();
        boolean overwrite = optionalBoolean(arguments, "overwrite", false);

        // ── Guardrails ────────────────────────────────────────────────────────
        if (!NAME_PATTERN.matcher(name).matches()) {
            return "Error: invalid tool name '" + name + "'. Use a snake_case identifier: 3-64 chars, "
                    + "lowercase letters, digits and underscores, starting with a letter.";
        }
        Language runtime = LANGUAGES.get(language);
        if (runtime == null) {
            return "Error: unsupported language '" + language + "'. Choose one of: python, bash, node.";
        }
        if (code.isBlank()) {
            return "Error: 'code' is empty. Provide the full script source for the tool.";
        }
        if (code.length() > MAX_CODE_CHARS) {
            return "Error: 'code' is too large (" + code.length() + " chars, limit " + MAX_CODE_CHARS
                    + "). Keep generated tools small and self-contained.";
        }
        if (solvedTask.length() < 15) {
            return "Error: 'solved_task' is too thin. Briefly describe the task you already solved in "
                    + "this conversation that this tool captures. Only create tools from work already done.";
        }
        // Parse the optional timeout up front so a malformed value is reported rather than silently dropped.
        Integer timeoutSeconds;
        try {
            timeoutSeconds = parseOptionalTimeout(arguments.get("timeout_seconds"));
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }

        LocalToolRegistry builtins = localToolRegistry.getIfAvailable();
        if (builtins != null && builtins.find(name) != null) {
            return "Error: '" + name + "' is the name of a built-in tool and cannot be redefined. "
                    + "Pick a different name.";
        }

        Workspace ws = ctx.workspace();
        Path manifestDir = ws.resolve(properties.jitToolDirectory());
        Path scriptDir = manifestDir.resolve("scripts");
        Path manifestPath = manifestDir.resolve(name + ".tool.json");
        Path scriptPath = scriptDir.resolve(name + "." + runtime.extension());

        boolean alreadyExists = jitTools.find(name, ws) != null || Files.exists(manifestPath);
        if (alreadyExists && !overwrite) {
            return "Error: a tool named '" + name + "' already exists. Reuse it, choose a different "
                    + "name, or pass overwrite=true to replace it.";
        }
        if (!alreadyExists) {
            int existing = jitTools.tools(ws).size();
            if (existing >= maxTools) {
                return "Error: this workspace already has " + existing + " generated tools (limit "
                        + maxTools + "). Reuse an existing tool, or remove one you no longer need from "
                        + properties.jitToolDirectory() + " before creating another.";
            }
        }

        // ── Validate the input schema, if supplied ─────────────────────────────
        Map<String, Object> inputSchema;
        Object rawSchema = arguments.get("input_schema");
        if (rawSchema == null) {
            inputSchema = Map.of("type", "object", "properties", Map.of());
        } else if (rawSchema instanceof Map<?, ?> map) {
            inputSchema = (Map<String, Object>) map;
        } else {
            return "Error: 'input_schema' must be a JSON object (a JSON-schema 'object' definition).";
        }

        // ── Write the script and the manifest ──────────────────────────────────
        Files.createDirectories(scriptDir);
        ensureNotTracked(manifestDir);
        Files.writeString(scriptPath, normalizeTrailingNewline(code));

        String scriptRelative = ws.relativize(scriptPath);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("name", name);
        manifest.put("description", description);
        manifest.put("command", runtime.command());
        manifest.put("args", List.of(scriptRelative));
        manifest.put("inputSchema", inputSchema);
        manifest.put("passArgumentsViaStdin", true);
        if (timeoutSeconds != null) {
            manifest.put("timeoutSeconds", timeoutSeconds);
        }
        // Record provenance for humans auditing generated tools; the registry ignores unknown fields.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("createdBy", "create_agent_tool");
        meta.put("solvedTask", solvedTask);
        manifest.put("_meta", meta);

        Files.writeString(manifestPath, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(manifest));

        log.info("Created JIT tool '{}' ({}) in workspace {}", name, language, ws.root());

        String verb = alreadyExists ? "Replaced" : "Created";
        return verb + " tool '" + name + "'.\n"
                + "  manifest: " + ws.relativize(manifestPath) + "\n"
                + "  script:   " + scriptRelative + "\n"
                + "It is loaded on the fly and callable on your next step — no restart needed. "
                + "Install any dependencies it needs, then test it by calling '" + name + "' once.";
    }

    /**
     * Writes a {@code .gitignore} into the generated-tools directory so the scripts and manifests are
     * never committed, satisfying the "lives in a subfolder that is not git tracked" requirement even
     * when the workspace is a fresh checkout whose root {@code .gitignore} doesn't already cover it.
     */
    private void ensureNotTracked(Path manifestDir) throws IOException {
        Files.createDirectories(manifestDir);
        Path gitignore = manifestDir.resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            Files.writeString(gitignore,
                    "# Runtime-generated agent tools (created by create_agent_tool) — do not commit.\n*\n");
        }
    }

    private static String normalizeTrailingNewline(String code) {
        return code.endsWith("\n") ? code : code + "\n";
    }

    /**
     * Parses the optional {@code timeout_seconds} argument: {@code null} when absent, otherwise a
     * positive integer. A value that is present but not a positive integer is an error the caller
     * surfaces to the model, rather than being silently dropped.
     */
    private static Integer parseOptionalTimeout(Object raw) {
        if (raw == null) {
            return null;
        }
        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            String text = raw.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                value = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "'timeout_seconds' must be a positive integer (got '" + raw + "').");
            }
        }
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "'timeout_seconds' must be a positive integer (got " + value + ").");
        }
        return value;
    }
}
