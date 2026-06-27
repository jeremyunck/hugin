package com.example.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Retrieves the version of the bouw CLI. */
@Component
public class BouwVersionTool implements LocalTool {

    private static final String COMMON_PATH_PREFIX = "/opt/homebrew/bin:/usr/local/bin:/opt/local/bin";
    private static final List<Path> COMMON_LAUNCHERS = List.of(
            Path.of("/usr/local/bin/bouw"),
            Path.of("/opt/homebrew/bin/bouw"));
    private static final Pattern VERSION_ASSIGNMENT = Pattern.compile("(?m)^BOUW_VERSION=\"([^\"]+)\"");
    private static final Pattern REPO_DIR_ASSIGNMENT = Pattern.compile("(?m)^REPO_DIR=\"([^\"]+)\"");

    private final Workspace workspace;
    private final Duration timeout;
    private final int maxChars;
    private final ObjectMapper objectMapper;

    public BouwVersionTool(Workspace workspace, LocalToolProperties properties, ObjectMapper objectMapper) {
        this.workspace = workspace;
        this.timeout = properties.bashTimeout();
        this.maxChars = properties.maxOutputChars();
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "bouw_version";
    }

    @Override
    public String description() {
        return "Get the installed version of the bouw CLI tool.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException, InterruptedException {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws IOException, InterruptedException {
        Optional<String> metadataVersion = resolveMetadataVersion(ctx.workspace().root());
        if (metadataVersion.isPresent()) {
            return metadataVersion.get();
        }

        List<List<String>> commands = List.of(
                List.of("bouw", "--version"),
                List.of("bouw", "version"));

        List<String> failures = new ArrayList<>();
        for (List<String> command : commands) {
            CommandResult result;
            try {
                result = run(command);
            } catch (IOException e) {
                failures.add(String.join(" ", command) + " failed: " + e.getMessage());
                continue;
            }
            String version = extractVersion(result.output());
            if (result.exitCode() == 0 && version != null) {
                return version;
            }
            failures.add(String.join(" ", command) + " exited with code " + result.exitCode()
                    + (result.output().isBlank() ? "" : "\n" + result.output().strip()));
        }

        return "Error: could not determine bouw version.\n" + String.join("\n\n", failures);
    }

    private Optional<String> resolveMetadataVersion(Path workspaceRoot) {
        String envVersion = System.getenv("BOUW_VERSION");
        if (isVersion(envVersion)) {
            return Optional.of(envVersion.strip());
        }

        List<Path> packageJsonCandidates = new ArrayList<>();
        addPackageJsonCandidate(packageJsonCandidates, System.getenv("BOUW_REPO_DIR"));
        addPackageJsonCandidate(packageJsonCandidates, System.getenv("REPO_DIR"));
        packageJsonCandidates.add(workspaceRoot.resolve("package.json"));
        addLauncherPackageJsonCandidates(packageJsonCandidates);

        Optional<LauncherMetadata> launcher = readLauncherMetadata();
        launcher.flatMap(LauncherMetadata::repoDir)
                .ifPresent(path -> packageJsonCandidates.add(path.resolve("package.json")));

        for (Path candidate : packageJsonCandidates) {
            Optional<String> version = readPackageJsonVersion(candidate);
            if (version.isPresent()) {
                return version;
            }
        }

        return launcher.flatMap(LauncherMetadata::version);
    }

    private static void addPackageJsonCandidate(List<Path> candidates, String directory) {
        if (directory != null && !directory.isBlank()) {
            candidates.add(Path.of(directory).resolve("package.json"));
        }
    }

    private static void addLauncherPackageJsonCandidates(List<Path> candidates) {
        List<Path> launchers = new ArrayList<>();
        String configuredLauncher = System.getenv("BOUW_LAUNCHER_PATH");
        if (configuredLauncher != null && !configuredLauncher.isBlank()) {
            launchers.add(Path.of(configuredLauncher));
        }
        launchers.addAll(COMMON_LAUNCHERS);

        for (Path launcher : launchers) {
            try {
                if (!Files.exists(launcher)) {
                    continue;
                }
                Path path = launcher.toRealPath();
                Path dir = Files.isDirectory(path) ? path : path.getParent();
                for (int i = 0; i < 6 && dir != null; i++) {
                    candidates.add(dir.resolve("package.json"));
                    dir = dir.getParent();
                }
            } catch (IOException ignored) {
                // try the next launcher path
            }
        }
    }

    private Optional<String> readPackageJsonVersion(Path packageJson) {
        try {
            if (!Files.isRegularFile(packageJson)) {
                return Optional.empty();
            }
            String version = objectMapper.readTree(packageJson.toFile()).path("version").asText(null);
            return isVersion(version) ? Optional.of(version.strip()) : Optional.empty();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<LauncherMetadata> readLauncherMetadata() {
        for (Path launcher : COMMON_LAUNCHERS) {
            try {
                if (!Files.isRegularFile(launcher)) {
                    continue;
                }
                String content = Files.readString(launcher, StandardCharsets.UTF_8);
                Optional<String> version = extractAssignment(content, VERSION_ASSIGNMENT)
                        .filter(BouwVersionTool::isVersion);
                Optional<Path> repoDir = extractAssignment(content, REPO_DIR_ASSIGNMENT)
                        .filter(value -> !value.isBlank())
                        .map(Path::of);
                if (version.isPresent() || repoDir.isPresent()) {
                    return Optional.of(new LauncherMetadata(version, repoDir));
                }
            } catch (IOException ignored) {
                // try the next common launcher path
            }
        }
        return Optional.empty();
    }

    private static Optional<String> extractAssignment(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? Optional.of(matcher.group(1).strip()) : Optional.empty();
    }

    private CommandResult run(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        String currentPath = System.getenv("PATH") != null ? System.getenv("PATH") : "";
        builder.environment().put("PATH", COMMON_PATH_PREFIX + ":" + currentPath);
        builder.redirectErrorStream(true);

        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> drain(process, output));
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            reader.join(1000);
            return new CommandResult(124, String.join(" ", command)
                    + " timed out after " + timeout.toSeconds() + "s.");
        }
        reader.join(2000);

        return new CommandResult(process.exitValue(), render(output));
    }

    private void drain(Process process, StringBuilder output) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            int ch;
            while ((ch = in.read()) != -1) {
                if (output.length() < maxChars) {
                    output.append((char) ch);
                }
            }
        } catch (IOException ignored) {
            // process output stream closed/interrupted — keep what we have
        }
    }

    private String render(StringBuilder output) {
        if (output.length() >= maxChars) {
            return output + "\n... [output truncated at " + maxChars + " characters]";
        }
        return output.toString();
    }

    static String extractVersion(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        String text = output.strip();
        if (text.startsWith("Usage: bouw")) {
            return null;
        }

        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.matches("v?\\d+\\.\\d+\\.\\d+.*")) {
                return trimmed.startsWith("v") ? trimmed.substring(1) : trimmed;
            }
            int packageAt = trimmed.indexOf("bouw-agent@");
            if (packageAt >= 0) {
                String candidate = trimmed.substring(packageAt + "bouw-agent@".length()).strip();
                int end = 0;
                while (end < candidate.length()
                        && !Character.isWhitespace(candidate.charAt(end))
                        && candidate.charAt(end) != ')') {
                    end++;
                }
                if (end > 0) {
                    return candidate.substring(0, end);
                }
            }
        }
        return null;
    }

    private static boolean isVersion(String value) {
        return value != null && value.strip().matches("v?\\d+\\.\\d+\\.\\d+.*");
    }

    private record CommandResult(int exitCode, String output) {}
    private record LauncherMetadata(Optional<String> version, Optional<Path> repoDir) {}
}
