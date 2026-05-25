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

    @BeforeEach
    void setUp() {
        properties = new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 30_000);
        workspace = new Workspace(properties);
    }

    @Test
    void writeThenReadRoundTrips() throws Exception {
        var write = new WriteFileTool(workspace);
        var read = new ReadFileTool(workspace, properties);

        String writeResult = write.execute(Map.of("path", "src/Hello.java", "content", "class Hello {}"));
        assertThat(writeResult).contains("Wrote");
        assertThat(Files.readString(tmp.resolve("src/Hello.java"))).isEqualTo("class Hello {}");

        String readResult = read.execute(Map.of("path", "src/Hello.java"));
        assertThat(readResult).isEqualTo("class Hello {}");
    }

    @Test
    void editReplacesUniqueOccurrence() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "foo bar baz");
        var edit = new EditFileTool(workspace);

        String result = edit.execute(Map.of(
                "path", "a.txt", "old_string", "bar", "new_string", "qux"));

        assertThat(result).contains("1 replacement");
        assertThat(Files.readString(tmp.resolve("a.txt"))).isEqualTo("foo qux baz");
    }

    @Test
    void editRejectsAmbiguousMatchUnlessReplaceAll() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "x x x");
        var edit = new EditFileTool(workspace);

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
    void pathsEscapingWorkspaceAreRejected() {
        var read = new ReadFileTool(workspace, properties);

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

        var read = new ReadFileTool(workspace, properties);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> read.execute(Map.of("path", "link/secret.txt")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the workspace root");
    }

    @Test
    void disabledRegistryExposesNoTools() {
        var disabled = new LocalToolProperties(false, tmp.toString(), Duration.ofSeconds(10), 30_000);
        var emptyRegistry = new LocalToolRegistry(
                List.of(new WriteFileTool(workspace)), disabled);

        assertThat(emptyRegistry.tools()).isEmpty();
        assertThat(emptyRegistry.find("write_file")).isNull();
    }
}
