package com.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CreateAgentToolTool}: it should write a runnable script + manifest that the
 * {@link JustInTimeToolRegistry} can immediately load and execute, while enforcing its guardrails.
 */
class CreateAgentToolToolTest {

    @TempDir
    Path tmp;

    private LocalToolProperties properties;
    private Workspace workspace;
    private JustInTimeToolRegistry jitTools;
    private CreateAgentToolTool tool;

    @BeforeEach
    void setUp() {
        properties = new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 30_000, List.of());
        workspace = new Workspace(properties);
        jitTools = new JustInTimeToolRegistry(properties, new ObjectMapper());
        tool = new CreateAgentToolTool(workspace, properties, jitTools, emptyRegistryProvider(),
                new ObjectMapper(), 24);
    }

    @Test
    void createsToolThatIsImmediatelyLoadableAndRunnable() throws Exception {
        String result = tool.execute(baseArgs("lookup_stock_ticker"), new ToolContext(workspace));

        assertThat(result).contains("Created tool 'lookup_stock_ticker'");
        assertThat(result).contains("no restart needed");

        // Script and manifest landed under the non-tracked jit-tools directory.
        Path scriptDir = tmp.resolve(".bouw/jit-tools/scripts");
        assertThat(Files.exists(scriptDir.resolve("lookup_stock_ticker.py"))).isTrue();
        assertThat(Files.exists(tmp.resolve(".bouw/jit-tools/lookup_stock_ticker.tool.json"))).isTrue();
        // A .gitignore keeps the generated code out of version control.
        assertThat(Files.readString(tmp.resolve(".bouw/jit-tools/.gitignore"))).contains("*");

        // The JIT registry picks it up on the next scan and can run it end-to-end.
        LocalTool created = jitTools.find("lookup_stock_ticker", workspace);
        assertThat(created).isNotNull();
        assertThat(created.inputSchema()).containsEntry("type", "object");

        String run = created.execute(Map.of("symbol", "ACME"), new ToolContext(workspace));
        assertThat(run).contains("exit code: 0");
        assertThat(run).contains("price for ACME");
    }

    @Test
    void rejectsInvalidName() throws Exception {
        Map<String, Object> args = baseArgs("Bad Name!");
        String result = tool.execute(args, new ToolContext(workspace));
        assertThat(result).contains("invalid tool name");
        assertThat(Files.exists(tmp.resolve(".bouw/jit-tools"))).isFalse();
    }

    @Test
    void rejectsThinSolvedTask() throws Exception {
        Map<String, Object> args = baseArgs("one_off");
        args.put("solved_task", "idk");
        String result = tool.execute(args, new ToolContext(workspace));
        assertThat(result).contains("solved_task");
    }

    @Test
    void returnsFriendlyErrorWhenCodeMissing() throws Exception {
        Map<String, Object> args = baseArgs("no_code");
        args.remove("code");
        String result = tool.execute(args, new ToolContext(workspace));
        assertThat(result).contains("'code' is empty");
    }

    @Test
    void reportsInvalidTimeoutInsteadOfDroppingIt() throws Exception {
        Map<String, Object> args = baseArgs("bad_timeout");
        args.put("timeout_seconds", "thirty");
        String result = tool.execute(args, new ToolContext(workspace));
        assertThat(result).contains("timeout_seconds");
        // Nothing should have been written when validation fails.
        assertThat(Files.exists(tmp.resolve(".bouw/jit-tools/bad_timeout.tool.json"))).isFalse();
    }

    @Test
    void recordsValidTimeoutInManifest() throws Exception {
        Map<String, Object> args = baseArgs("timed_tool");
        args.put("timeout_seconds", 45);
        tool.execute(args, new ToolContext(workspace));
        String manifest = Files.readString(tmp.resolve(".bouw/jit-tools/timed_tool.tool.json"));
        assertThat(manifest).contains("\"timeoutSeconds\" : 45");
    }

    @Test
    void refusesToShadowBuiltinTool() throws Exception {
        LocalToolRegistry builtins = new LocalToolRegistry(
                List.of(new BashCommandTool(workspace, properties)), properties);
        CreateAgentToolTool guarded = new CreateAgentToolTool(workspace, properties, jitTools,
                fixedRegistryProvider(builtins), new ObjectMapper(), 24);

        String result = guarded.execute(baseArgs("run_bash"), new ToolContext(workspace));
        assertThat(result).contains("built-in tool");
    }

    @Test
    void refusesToOverwriteWithoutFlagButReplacesWithIt() throws Exception {
        assertThat(tool.execute(baseArgs("dup"), new ToolContext(workspace))).contains("Created tool 'dup'");

        String blocked = tool.execute(baseArgs("dup"), new ToolContext(workspace));
        assertThat(blocked).contains("already exists");

        Map<String, Object> overwrite = baseArgs("dup");
        overwrite.put("description", "second version");
        overwrite.put("overwrite", true);
        String replaced = tool.execute(overwrite, new ToolContext(workspace));
        assertThat(replaced).contains("Replaced tool 'dup'");
        assertThat(jitTools.find("dup", workspace).description()).isEqualTo("second version");
    }

    @Test
    void enforcesMaxToolLimit() throws Exception {
        CreateAgentToolTool limited = new CreateAgentToolTool(workspace, properties, jitTools,
                emptyRegistryProvider(), new ObjectMapper(), 1);

        assertThat(limited.execute(baseArgs("first_tool"), new ToolContext(workspace)))
                .contains("Created tool 'first_tool'");
        String second = limited.execute(baseArgs("second_tool"), new ToolContext(workspace));
        assertThat(second).contains("limit 1");
    }

    private Map<String, Object> baseArgs(String name) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", name);
        args.put("description", "Look up a stock ticker price.");
        args.put("language", "python");
        args.put("code", """
                import json, sys
                data = json.load(sys.stdin)
                print(f"price for {data['symbol']}: 123.45")
                """);
        args.put("input_schema", Map.of(
                "type", "object",
                "properties", Map.of("symbol", Map.of("type", "string")),
                "required", List.of("symbol")));
        args.put("solved_task", "Fetched a stock price with a yfinance run_bash script just now.");
        return args;
    }

    private static ObjectProvider<LocalToolRegistry> emptyRegistryProvider() {
        return fixedRegistryProvider(null);
    }

    /** Minimal ObjectProvider stub returning a fixed (possibly null) registry. */
    private static ObjectProvider<LocalToolRegistry> fixedRegistryProvider(LocalToolRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public LocalToolRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public LocalToolRegistry getObject() {
                return registry;
            }

            @Override
            public LocalToolRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public LocalToolRegistry getIfUnique() {
                return registry;
            }
        };
    }
}
