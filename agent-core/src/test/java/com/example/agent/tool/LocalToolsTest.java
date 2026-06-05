package com.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    void createThenReadPdfRoundTrips() throws Exception {
        var create = new CreatePdfTool(workspace, noDenyList);
        var read = new ReadPdfTool(workspace, properties, noDenyList);

        String writeResult = create.execute(Map.of(
                "path", "docs/report.pdf",
                "title", "Quarterly Report",
                "content", "Hello PDF\n\nSecond page paragraph? No, just text."));

        assertThat(writeResult).contains("Wrote PDF to docs/report.pdf");
        assertThat(Files.exists(tmp.resolve("docs/report.pdf"))).isTrue();

        String readResult = read.execute(Map.of("path", "docs/report.pdf"));
        assertThat(readResult).contains("Title: Quarterly Report");
        assertThat(readResult).contains("Hello PDF");
        assertThat(readResult).contains("Second page paragraph? No, just text.");
    }

    @Test
    void createThenReadPdfRoundTripsUnicodeText() throws Exception {
        var create = new CreatePdfTool(workspace, noDenyList);
        var read = new ReadPdfTool(workspace, properties, noDenyList);

        String writeResult = create.execute(Map.of(
                "path", "docs/unicode.pdf",
                "title", "Unicode Report",
                "content", "Résumé — café — π — Привет"));

        assertThat(writeResult).contains("Wrote PDF to docs/unicode.pdf");
        assertThat(Files.exists(tmp.resolve("docs/unicode.pdf"))).isTrue();

        String readResult = read.execute(Map.of("path", "docs/unicode.pdf"));
        assertThat(readResult).contains("Title: Unicode Report");
        assertThat(readResult).contains("Résumé");
        assertThat(readResult).contains("café");
        assertThat(readResult).contains("Привет");
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
    void findFilesMatchesByNameRecursively() throws Exception {
        Files.createDirectories(tmp.resolve("src/main"));
        Files.writeString(tmp.resolve("src/main/App.java"), "x");
        Files.writeString(tmp.resolve("README.md"), "x");
        var find = new FindFilesTool(workspace);

        String result = find.execute(Map.of("pattern", "*.java"));

        assertThat(result).contains("src/main/App.java");
        assertThat(result).doesNotContain("README.md");
    }

    @Test
    void findFilesMatchesByPathGlob() throws Exception {
        Files.createDirectories(tmp.resolve("src/config"));
        Files.writeString(tmp.resolve("src/config/app.yml"), "x");
        Files.writeString(tmp.resolve("top.yml"), "x");
        var find = new FindFilesTool(workspace);

        String result = find.execute(Map.of("pattern", "src/**/*.yml"));

        assertThat(result).contains("src/config/app.yml");
        assertThat(result).doesNotContain("top.yml");
    }

    @Test
    void findFilesSkipsIgnoredDirectories() throws Exception {
        Files.createDirectories(tmp.resolve("target/classes"));
        Files.writeString(tmp.resolve("target/classes/Generated.java"), "x");
        Files.writeString(tmp.resolve("Real.java"), "x");
        var find = new FindFilesTool(workspace);

        String result = find.execute(Map.of("pattern", "*.java"));

        assertThat(result).contains("Real.java");
        assertThat(result).doesNotContain("Generated.java");
    }

    @Test
    void findFilesReturnsMessageWhenNoneMatch() throws Exception {
        Files.writeString(tmp.resolve("a.txt"), "x");
        var find = new FindFilesTool(workspace);

        assertThat(find.execute(Map.of("pattern", "*.java"))).isEqualTo("No files found.");
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
    void runBashHonorsExplicitShellAndNonLoginMode() throws Exception {
        var props = new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 30_000,
                List.of(), "/bin/sh", false);
        var bash = new BashCommandTool(workspace, props);

        String result = bash.execute(Map.of("command", "echo no-login-shell"));

        assertThat(result).contains("exit code: 0");
        assertThat(result).contains("no-login-shell");
    }

    @Test
    void runBashRunsThroughLoginShellByDefault() throws Exception {
        // Default properties resolve to the user's login shell with -l, so the command still runs;
        // login mode is what lets PATH entries from the user's profile (e.g. Homebrew) resolve.
        var props = new LocalToolProperties(true, tmp.toString(), Duration.ofSeconds(10), 30_000, List.of());
        var bash = new BashCommandTool(workspace, props);

        String result = bash.execute(Map.of("command", "echo login-shell-ok"));

        assertThat(result).contains("exit code: 0");
        assertThat(result).contains("login-shell-ok");
    }

    @Test
    void selfUpdateInputSchemaHasNoRequiredArgs() {
        var update = new SelfUpdateTool(workspace, properties, Optional.empty());

        var schema = update.inputSchema();

        assertThat(schema.get("type")).isEqualTo("object");
        assertThat(schema.get("required")).asList().isEmpty();
    }

    @Test
    void huginVersionExtractsPlainCliVersion() {
        assertThat(HuginVersionTool.extractVersion("0.1.6\n")).isEqualTo("0.1.6");
    }

    @Test
    void huginVersionExtractsNpmGlobalVersion() {
        assertThat(HuginVersionTool.extractVersion("/opt/homebrew/lib\n└── hugin-agent@0.1.6\n"))
                .isEqualTo("0.1.6");
    }

    @Test
    void huginVersionIgnoresLauncherUsageOutput() {
        assertThat(HuginVersionTool.extractVersion("Usage: hugin [command]\n\n  logs Stream service logs\n"))
                .isNull();
    }

    @Test
    void huginVersionReadsPackageJsonFromWorkspace() throws Exception {
        Files.writeString(tmp.resolve("package.json"), """
                {
                  "name": "hugin-agent",
                  "version": "9.8.7"
                }
                """);
        var tool = new HuginVersionTool(workspace, properties, new ObjectMapper());

        String result = tool.execute(Map.of());

        assertThat(result).isEqualTo("9.8.7");
    }

    @Test
    void resolveVersionExtractsProjectVersionSkippingParent() throws Exception {
        Files.writeString(tmp.resolve("pom.xml"),
                "<project>\n"
                + "  <parent><version>3.5.0</version></parent>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>my-app</artifactId>\n"
                + "  <version>1.2.3</version>\n"
                + "</project>");

        assertThat(SelfUpdateTool.resolveVersion(tmp)).isEqualTo("1.2.3");
    }

    @Test
    void resolveVersionUsesFirstVersionWhenNoParentElement() throws Exception {
        Files.writeString(tmp.resolve("pom.xml"),
                "<project>\n"
                + "  <groupId>com.example</groupId>\n"
                + "  <artifactId>my-app</artifactId>\n"
                + "  <version>2.0.0</version>\n"
                + "</project>");

        assertThat(SelfUpdateTool.resolveVersion(tmp)).isEqualTo("2.0.0");
    }

    @Test
    void resolveVersionIgnoresDependencyAndPluginVersions() throws Exception {
        Files.writeString(tmp.resolve("pom.xml"),
                "<project>\n"
                + "  <parent><version>3.5.0</version></parent>\n"
                + "  <artifactId>my-app</artifactId>\n"
                + "  <version>4.0.0</version>\n"
                + "  <dependencies>\n"
                + "    <dependency><groupId>org.foo</groupId><version>9.9.9</version></dependency>\n"
                + "  </dependencies>\n"
                + "  <build><plugins><plugin><version>1.0</version></plugin></plugins></build>\n"
                + "</project>");

        assertThat(SelfUpdateTool.resolveVersion(tmp)).isEqualTo("4.0.0");
    }

    @Test
    void resolveVersionReturnsUnknownWhenNoPomAndNoGit() {
        // tmp is an empty directory with no pom.xml and no git repo
        assertThat(SelfUpdateTool.resolveVersion(tmp)).isEqualTo("unknown");
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
