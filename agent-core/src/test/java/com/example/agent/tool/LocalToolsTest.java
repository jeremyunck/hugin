package com.example.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the built-in {@link LocalTool}s, exercised against a temporary workspace.
 */
class LocalToolsTest {

    @TempDir
    Path tmp;

    private Workspace workspace;
    private LocalToolProperties properties;
    private PathDenyList noDenyList;

    @BeforeEach
    void setUp() {
        properties = new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 30_000, List.of());
        workspace = new Workspace(properties);
        noDenyList = new PathDenyList(properties);
    }

    @Test
    void writeThenReadRoundTrips() throws Exception {
        var write = new WriteFileTool(workspace, noDenyList);
        var read = new ReadFileTool(workspace, properties, noDenyList);

        String writeResult = write.execute(Map.of("path", "src/Hello.java", "content", "class Hello {}"));
        assertThat(writeResult).contains("Wrote");
        assertThat(Files.readString(tmp.resolve("src/Hello.java"))).isEqualTo("class Hello {}");

        String readResult = read.execute(Map.of("path", "src/Hello.java"));
        assertThat(readResult).isEqualTo("class Hello {}");
    }

    @Test
    void editReplacesUniqueOccurrence() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "foo bar baz");
        var edit = new EditFileTool(workspace, noDenyList);

        String result = edit.execute(Map.of(
                "path", "a.txt", "old_string", "bar", "new_string", "qux"));

        assertThat(result).contains("1 replacement");
        assertThat(Files.readString(tmp.resolve("a.txt"))).isEqualTo("foo qux baz");
    }

    @Test
    void editRejectsAmbiguousMatchUnlessReplaceAll() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "x x x");
        var edit = new EditFileTool(workspace, noDenyList);

        String ambiguous = edit.execute(Map.of(
                "path", "a.txt", "old_string", "x", "new_string", "y"));
        assertThat(ambiguous).contains("appears 3 times");
        assertThat(Files.readString(tmp.resolve("a.txt"))).isEqualTo("x x x");

        String all = edit.execute(Map.of(
                "path", "a.txt", "old_string", "x", "new_string", "y", "replace_all", true));
        assertThat(all).contains("3 replacements");
        assertThat(Files.readString(tmp.resolve("a.txt"))).isEqualTo("y y y");
    }

    @Test
    void listFilesShowsEntriesAndMarksDirectories() throws Exception {
        Files.createDirectories(tmp.resolve("dir"));
        Files.writeString(tmp.resolve("file.txt"), "hi");
        var list = new ListFilesTool(workspace);

        String result = list.execute(Map.of());

        assertThat(result).contains("dir/");
        assertThat(result).contains("file.txt");
    }

    @Test
    void grepSearchFindsMatchesWithLineNumbers() throws Exception {
        Files.writeString(tmp.resolve("Main.java"), "line one\nTODO fix this\nline three");
        var grep = new GrepSearchTool(workspace);

        String result = grep.execute(Map.of("pattern", "TODO", "glob", "*.java"));

        assertThat(result).contains("Main.java:2:");
        assertThat(result).contains("TODO fix this");
    }

    @Test
    void grepSearchReturnsNoMatchesMessage() throws Exception {
        Files.writeString(tmp.resolve("Main.java"), "nothing here");
        var grep = new GrepSearchTool(workspace);

        assertThat(grep.execute(Map.of("pattern", "absent"))).isEqualTo("No matches found.");
    }

    @Test
    void runBashReturnsExitCodeAndOutput() throws Exception {
        var bash = new BashCommandTool(workspace, properties);

        String result = bash.execute(Map.of("command", "echo hello-world"));

        assertThat(result).contains("exit code: 0");
        assertThat(result).contains("hello-world");
    }

    @Test
    void runBashSurfacesNonZeroExitCode() throws Exception {
        var bash = new BashCommandTool(workspace, properties);

        String result = bash.execute(Map.of("command", "exit 3"));

        assertThat(result).contains("exit code: 3");
    }

    @Test
    void selfUpdateInputSchemaHasNoRequiredArgs() {
        var update = new SelfUpdateTool(workspace, properties);

        var schema = update.inputSchema();

        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("required")).asList().isEmpty();
    }

    @Test
    void addCronJobSchemaRequiresScheduleAndScriptPath() {
        var cron = new AddCronJobTool(properties);

        var schema = cron.inputSchema();

        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("required")).asList().containsExactly("schedule", "script_path");
    }

    @Test
    void addCronJobRejectsMissingSchedule() {
        var cron = new AddCronJobTool(properties);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cron.execute(Map.of("script_path", "/some/script.sh")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schedule");
    }

    @Test
    void addCronJobRejectsMissingScriptPath() {
        var cron = new AddCronJobTool(properties);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> cron.execute(Map.of("schedule", "0 5 * * *")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("script_path");
    }

    @Test
    void pathsEscapingWorkspaceAreRejected() {
        var read = new ReadFileTool(workspace, properties, noDenyList);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> read.execute(Map.of("path", "../outside.txt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the workspace root");
    }

    @Test
    void symlinkEscapingWorkspaceIsRejected() throws Exception {
        Path outside = Files.createTempDirectory("outside-workspace");
        Files.writeString(outside.resolve("secret.txt"), "top secret");
        Path link = tmp.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported on this platform");
        }

        var read = new ReadFileTool(workspace, properties, noDenyList);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> read.execute(Map.of("path", "link/secret.txt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the workspace root");
    }

    @Test
    void writeThroughDanglingSymlinkIsRejected() throws Exception {
        Path link = tmp.resolve("badlink");
        try {
            Files.createSymbolicLink(link, Path.of("/nonexistent-outside-target/dir"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unsupported on this platform");
        }

        var write = new WriteFileTool(workspace, noDenyList);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> write.execute(Map.of("path", "badlink/file.txt", "content", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disabledRegistryExposesNoTools() {
        var disabled = new LocalToolProperties(false, tmp.toString(), Duration.ofSeconds(10), 30_000, List.of());
        var emptyRegistry = new LocalToolRegistry(
                List.of(new WriteFileTool(workspace, noDenyList)), disabled);

        assertThat(emptyRegistry.tools()).isEmpty();
        assertThat(emptyRegistry.find("write_file")).isNull();
    }

    // ------------------------------------------------------------------
    // Deny list
    // ------------------------------------------------------------------

    @Test
    void readFileDeniedByGlobPattern() throws Exception {
        Files.createDirectories(tmp.resolve("secrets"));
        Files.writeString(tmp.resolve("secrets/key.pem"), "private key");
        var denyList = new PathDenyList(
                new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 30_000,
                        List.of("secrets/**")));
        var read = new ReadFileTool(workspace, properties, denyList);

        String result = read.execute(Map.of("path", "secrets/key.pem"));

        assertThat(result).startsWith("Error: access to 'secrets/key.pem' is denied");
        assertThat(Files.readString(tmp.resolve("secrets/key.pem"))).isEqualTo("private key");
    }

    @Test
    void writeFileDeniedByGlobPattern() throws Exception {
        var denyList = new PathDenyList(
                new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 30_000,
                        List.of("**/.env")));
        var write = new WriteFileTool(workspace, denyList);

        String result = write.execute(Map.of("path", ".env", "content", "SECRET=1"));

        assertThat(result).startsWith("Error: access to '.env' is denied");
        assertThat(Files.exists(tmp.resolve(".env"))).isFalse();
    }

    @Test
    void editFileDeniedByGlobPattern() throws Exception {
        Files.writeString(tmp.resolve("config.yml"), "key: value");
        var denyList = new PathDenyList(
                new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 30_000,
                        List.of("*.yml")));
        var edit = new EditFileTool(workspace, denyList);

        String result = edit.execute(Map.of(
                "path", "config.yml", "old_string", "key: value", "new_string", "key: hacked"));

        assertThat(result).startsWith("Error: access to 'config.yml' is denied");
        assertThat(Files.readString(tmp.resolve("config.yml"))).isEqualTo("key: value");
    }

    @Test
    void denyListDoesNotBlockNonMatchingPaths() throws Exception {
        var denyList = new PathDenyList(
                new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 30_000,
                        List.of("secrets/**")));
        var write = new WriteFileTool(workspace, denyList);
        var read = new ReadFileTool(workspace, properties, denyList);

        String writeResult = write.execute(Map.of("path", "safe.txt", "content", "ok"));
        assertThat(writeResult).contains("Wrote");

        String readResult = read.execute(Map.of("path", "safe.txt"));
        assertThat(readResult).isEqualTo("ok");
    }

    @Test
    void denyPatternMatchesNestedPaths() throws Exception {
        Files.createDirectories(tmp.resolve("secrets/nested"));
        Files.writeString(tmp.resolve("secrets/nested/key.pem"), "nested key");
        var denyList = new PathDenyList(
                new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 30_000,
                        List.of("secrets/**")));
        var read = new ReadFileTool(workspace, properties, denyList);

        String result = read.execute(Map.of("path", "secrets/nested/key.pem"));

        assertThat(result).startsWith("Error: access to 'secrets/nested/key.pem' is denied");
    }
}
