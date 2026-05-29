package com.example.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Additional unit tests for the built-in {@link LocalTool}s that cover branches not exercised
 * by {@link LocalToolsTest}: recursive listing, error paths, truncation, and the default helper
 * methods on the {@link LocalTool} interface.
 */
class LocalToolsExtendedTest {

    @TempDir
    Path tmp;

    private Workspace workspace;
    private LocalToolProperties properties;
    private PathDenyList noDenyList;

    @BeforeEach
    void setUp() {
        properties = new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 100, List.of());
        workspace = new Workspace(properties);
        noDenyList = new PathDenyList(properties);
    }

    // ------------------------------------------------------------------
    // ListFilesTool
    // ------------------------------------------------------------------

    @Test
    void listFilesRecursively() throws Exception {
        Path sub = tmp.resolve("sub");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("deep.txt"), "content");
        Files.writeString(tmp.resolve("root.txt"), "content");
        var list = new ListFilesTool(workspace);

        String result = list.execute(Map.of("recursive", true));

        assertThat(result).contains("root.txt");
        assertThat(result).contains("sub/");
        assertThat(result).contains("sub/deep.txt");
    }

    @Test
    void listFilesNonExistentDirReturnsError() throws Exception {
        var list = new ListFilesTool(workspace);

        String result = list.execute(Map.of("path", "no-such-dir"));

        assertThat(result).startsWith("Error: directory does not exist:");
        assertThat(result).contains("no-such-dir");
    }

    @Test
    void listFilesNotADirectoryReturnsError() throws Exception {
        Files.writeString(tmp.resolve("file.txt"), "I am a file");
        var list = new ListFilesTool(workspace);

        String result = list.execute(Map.of("path", "file.txt"));

        assertThat(result).startsWith("Error: path is not a directory:");
        assertThat(result).contains("file.txt");
    }

    @Test
    void listFilesEmptyDirReturnsEmptyMessage() throws Exception {
        Files.createDirectories(tmp.resolve("empty"));
        var list = new ListFilesTool(workspace);

        String result = list.execute(Map.of("path", "empty"));

        assertThat(result).isEqualTo("(empty directory)");
    }

    // ------------------------------------------------------------------
    // ReadFileTool
    // ------------------------------------------------------------------

    @Test
    void readEmptyFileReturnsPlaceholder() throws Exception {
        Files.writeString(tmp.resolve("empty.txt"), "");
        var read = new ReadFileTool(workspace, properties, noDenyList);

        String result = read.execute(Map.of("path", "empty.txt"));

        assertThat(result).isEqualTo("(file is empty)");
    }

    @Test
    void readNonExistentFileReturnsError() throws Exception {
        var read = new ReadFileTool(workspace, properties, noDenyList);

        String result = read.execute(Map.of("path", "ghost.txt"));

        assertThat(result).startsWith("Error: file does not exist:");
        assertThat(result).contains("ghost.txt");
    }

    @Test
    void readDirectoryReturnsError() throws Exception {
        Files.createDirectories(tmp.resolve("adir"));
        var read = new ReadFileTool(workspace, properties, noDenyList);

        String result = read.execute(Map.of("path", "adir"));

        assertThat(result).startsWith("Error: path is a directory, not a file:");
        assertThat(result).contains("adir");
    }

    @Test
    void readFileTruncatesLargeContent() throws Exception {
        // properties.maxOutputChars() == 100; write 150 chars
        String bigContent = "a".repeat(150);
        Files.writeString(tmp.resolve("big.txt"), bigContent);
        var read = new ReadFileTool(workspace, properties, noDenyList);

        String result = read.execute(Map.of("path", "big.txt"));

        assertThat(result).startsWith("a".repeat(100));
        assertThat(result).contains("[truncated 50 characters]");
    }

    // ------------------------------------------------------------------
    // WriteFileTool
    // ------------------------------------------------------------------

    @Test
    void writeToDirectoryPathReturnsError() throws Exception {
        Files.createDirectories(tmp.resolve("existingdir"));
        var write = new WriteFileTool(workspace, noDenyList);

        String result = write.execute(Map.of("path", "existingdir", "content", "data"));

        assertThat(result).startsWith("Error: path is a directory, not a file:");
        assertThat(result).contains("existingdir");
    }

    // ------------------------------------------------------------------
    // LocalTool default methods: presentString
    // ------------------------------------------------------------------

    /** Anonymous inline implementation to test the default interface methods directly. */
    private final LocalTool stub = new LocalTool() {
        @Override public String name() { return "stub"; }
        @Override public String description() { return "stub"; }
        @Override public Map<String, Object> inputSchema() { return Map.of(); }
        @Override public String execute(Map<String, Object> arguments) { return "ok"; }
    };

    @Test
    void presentStringThrowsWhenKeyMissing() {
        assertThatThrownBy(() -> stub.presentString(Map.of(), "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required argument: 'key'");
    }

    @Test
    void presentStringAllowsEmpty() {
        // presentString must not throw when the value is present but empty.
        String result = stub.presentString(Map.of("key", ""), "key");
        assertThat(result).isEqualTo("");
    }

    @Test
    void presentStringThrowsWhenValueIsNull() {
        Map<String, Object> args = new HashMap<>();
        args.put("key", null);
        assertThatThrownBy(() -> stub.presentString(args, "key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required argument: 'key'");
    }

    // ------------------------------------------------------------------
    // LocalTool default methods: optionalInt
    // ------------------------------------------------------------------

    @Test
    void optionalIntParsesStringValue() {
        int result = stub.optionalInt(Map.of("n", "42"), "n", 0);
        assertThat(result).isEqualTo(42);
    }

    @Test
    void optionalIntHandlesNumberInstance() {
        int result = stub.optionalInt(Map.of("n", 7), "n", 0);
        assertThat(result).isEqualTo(7);
    }

    @Test
    void optionalIntReturnsDefaultForNull() {
        Map<String, Object> args = new HashMap<>();
        args.put("n", null);
        int result = stub.optionalInt(args, "n", 99);
        assertThat(result).isEqualTo(99);
    }

    @Test
    void optionalIntReturnsDefaultForMissingKey() {
        int result = stub.optionalInt(Map.of(), "n", 55);
        assertThat(result).isEqualTo(55);
    }

    @Test
    void optionalIntReturnsDefaultForUnparseableString() {
        int result = stub.optionalInt(Map.of("n", "not-a-number"), "n", 7);
        assertThat(result).isEqualTo(7);
    }

    // ------------------------------------------------------------------
    // LocalTool default methods: optionalBoolean
    // ------------------------------------------------------------------

    @Test
    void optionalBooleanParsesStringTrue() {
        boolean result = stub.optionalBoolean(Map.of("flag", "true"), "flag", false);
        assertThat(result).isTrue();
    }

    @Test
    void optionalBooleanParsesStringFalse() {
        boolean result = stub.optionalBoolean(Map.of("flag", "false"), "flag", true);
        assertThat(result).isFalse();
    }

    @Test
    void optionalBooleanHandlesBooleanInstance() {
        assertThat(stub.optionalBoolean(Map.of("flag", Boolean.TRUE), "flag", false)).isTrue();
        assertThat(stub.optionalBoolean(Map.of("flag", Boolean.FALSE), "flag", true)).isFalse();
    }

    @Test
    void optionalBooleanReturnsDefaultWhenAbsent() {
        assertThat(stub.optionalBoolean(Map.of(), "flag", true)).isTrue();
        assertThat(stub.optionalBoolean(Map.of(), "flag", false)).isFalse();
    }

    // ------------------------------------------------------------------
    // GrepSearchTool — additional coverage
    // ------------------------------------------------------------------

    @Test
    void grepSearchRecursivelyFindsMatchesInSubdirectories() throws Exception {
        Path sub = tmp.resolve("pkg");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("Foo.java"), "public class Foo {\n    // FIXME urgent\n}");
        Files.writeString(tmp.resolve("root.txt"), "no match here");
        var grep = new GrepSearchTool(workspace);

        String result = grep.execute(Map.of("pattern", "FIXME"));

        assertThat(result).contains("pkg/Foo.java:2:");
        assertThat(result).contains("FIXME urgent");
        assertThat(result).doesNotContain("root.txt");
    }

    @Test
    void grepSearchGlobFilterExcludesNonMatchingFiles() throws Exception {
        Files.writeString(tmp.resolve("Main.java"), "TODO fix");
        Files.writeString(tmp.resolve("notes.txt"), "TODO fix");
        var grep = new GrepSearchTool(workspace);

        String result = grep.execute(Map.of("pattern", "TODO", "glob", "*.java"));

        assertThat(result).contains("Main.java");
        assertThat(result).doesNotContain("notes.txt");
    }

    @Test
    void grepSearchIgnoreCaseFindsMixedCaseMatches() throws Exception {
        Files.writeString(tmp.resolve("mixed.txt"), "Hello World\nhello world\nHELLO WORLD");
        var grep = new GrepSearchTool(workspace);

        String result = grep.execute(Map.of("pattern", "hello", "ignore_case", true));

        assertThat(result).contains("mixed.txt:1:");
        assertThat(result).contains("mixed.txt:2:");
        assertThat(result).contains("mixed.txt:3:");
    }

    @Test
    void grepSearchReturnsErrorForInvalidRegex() throws Exception {
        var grep = new GrepSearchTool(workspace);

        String result = grep.execute(Map.of("pattern", "[invalid"));

        assertThat(result).startsWith("Error: invalid regular expression:");
    }

    @Test
    void grepSearchReturnsErrorForNonExistentPath() throws Exception {
        var grep = new GrepSearchTool(workspace);

        String result = grep.execute(Map.of("pattern", "anything", "path", "no-such-path"));

        assertThat(result).startsWith("Error: path does not exist:");
    }

    @Test
    void grepSearchOnSingleFileDirectly() throws Exception {
        Files.writeString(tmp.resolve("single.txt"), "line one\ntarget line\nline three");
        var grep = new GrepSearchTool(workspace);

        String result = grep.execute(Map.of("pattern", "target", "path", "single.txt"));

        assertThat(result).contains("single.txt:2:");
        assertThat(result).contains("target line");
    }
}
