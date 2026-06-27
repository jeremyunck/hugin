package com.example.integration.service;

import com.example.agent.model.ChatMessage;
import com.example.agent.tool.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
public class BugReportService {

    private static final DateTimeFormatter DIRECTORY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    private static final long MAX_LOG_BYTES = 256 * 1024;

    private final ObjectMapper objectMapper;
    private final BugReportCatalogService bugReportCatalog;
    private final Workspace defaultWorkspace;
    private final Path agentHome;
    private final Clock clock;

    @Autowired
    public BugReportService(ObjectMapper objectMapper,
                            BugReportCatalogService bugReportCatalog,
                            Workspace defaultWorkspace,
                            @Value("${agent.home:${user.home}/.bouw}") String agentHome) {
        this(objectMapper, bugReportCatalog, defaultWorkspace, Path.of(agentHome), Clock.systemDefaultZone());
    }

    BugReportService(ObjectMapper objectMapper,
                     BugReportCatalogService bugReportCatalog,
                     Workspace defaultWorkspace,
                     Path agentHome,
                     Clock clock) {
        this.objectMapper = objectMapper;
        this.bugReportCatalog = bugReportCatalog;
        this.defaultWorkspace = defaultWorkspace;
        this.agentHome = agentHome.toAbsolutePath().normalize();
        this.clock = clock;
    }

    public BugReportFile writeReport(String sessionId,
                                     String title,
                                     String owner,
                                     String agentId,
                                     String sandboxId,
                                     List<ChatMessage> history,
                                     JsonNode clientThread,
                                     JsonNode clientContext) {
        Workspace workspace = defaultWorkspace;
        LocalDateTime now = LocalDateTime.now(clock);
        String safeTitle = sanitizeTitle(title);
        String fileName = FILE_FORMAT.format(now) + "-" + safeTitle + ".txt";
        String dateDir = DIRECTORY_FORMAT.format(LocalDate.now(clock));
        Path report = workspace.resolve("bug-reports/" + dateDir + "/" + fileName);
        List<LogAttachment> logs = collectLogs(workspace);

        StringBuilder out = new StringBuilder();
        out.append("Bouw Bug Report\n");
        out.append("================\n\n");
        out.append("Generated at: ").append(now).append('\n');
        out.append("Title: ").append(blankFallback(title, "Untitled chat")).append('\n');
        out.append("Session ID: ").append(blankFallback(sessionId, "(none)")).append('\n');
        out.append("Owner: ").append(blankFallback(owner, "(unknown)")).append('\n');
        out.append("Agent ID: ").append(blankFallback(agentId, "(none)")).append('\n');
        out.append("Sandbox ID: ").append(blankFallback(sandboxId, "(none)")).append('\n');
        out.append("Workspace root: ").append(workspace.root()).append('\n');
        out.append("Saved report: ").append(report).append('\n');

        appendJsonSection(out, "Client Context", clientContext);
        appendJsonSection(out, "Client Thread Snapshot", clientThread);
        appendJsonSection(out, "Server Conversation History", objectMapper.valueToTree(history));

        out.append("\nRuntime Logs\n");
        out.append("------------\n");
        if (logs.isEmpty()) {
            out.append("No configured log files were found.\n");
        } else {
            for (LogAttachment log : logs) {
                out.append("\n# ").append(log.label()).append('\n');
                out.append("Path: ").append(log.path()).append("\n\n");
                out.append(log.content());
                if (!log.content().endsWith("\n")) {
                    out.append('\n');
                }
            }
        }

        String body = out.toString();
        try {
            Files.createDirectories(report.getParent());
            Files.writeString(report, body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write bug report: " + e.getMessage(), e);
        }

        String relativePath = workspace.relativize(report);
        Optional<BugReportCatalogService.StoredBugReport> savedBug = bugReportCatalog.save(
                owner,
                title,
                sessionId,
                agentId,
                sandboxId,
                relativePath,
                body);
        return new BugReportFile(
                savedBug.map(BugReportCatalogService.StoredBugReport::id).orElse(null),
                relativePath,
                report.toString(),
                workspace.root().toString(),
                // Expose workspace-relative log labels (not absolute host paths) so the API response
                // does not disclose the server's filesystem layout.
                logs.stream().map(LogAttachment::label).toList());
    }

    private void appendJsonSection(StringBuilder out, String title, JsonNode payload) {
        out.append("\n").append(title).append("\n");
        out.append("-".repeat(title.length())).append("\n");
        try {
            out.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)).append('\n');
        } catch (IOException e) {
            out.append("{\"error\":\"Could not serialize section: ")
                    .append(e.getMessage())
                    .append("\"}\n");
        }
    }

    private List<LogAttachment> collectLogs(Workspace workspace) {
        Set<Path> candidates = new LinkedHashSet<>();
        candidates.add(agentHome.resolve("logs/bouw.log"));
        candidates.add(workspace.root().resolve(".data/logs/server.out.log"));
        candidates.add(workspace.root().resolve(".data/logs/server.err.log"));
        candidates.add(workspace.root().resolve(".data/logs/update.out.log"));
        candidates.add(workspace.root().resolve(".data/logs/update.err.log"));

        List<LogAttachment> result = new ArrayList<>();
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
                continue;
            }
            try {
                result.add(new LogAttachment(labelFor(normalized, workspace), normalized, readLog(normalized)));
            } catch (IOException ignored) {
                // Skip unreadable logs rather than failing the bug-report export.
            }
        }
        return result;
    }

    private String readLog(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= MAX_LOG_BYTES) {
            return Files.readString(path);
        }
        int tailSize = (int) Math.min(size, MAX_LOG_BYTES);
        byte[] bytes = new byte[tailSize];
        try (var channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            channel.position(size - tailSize);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) {
                // Read the requested tail window.
            }
        }
        String tail = new String(bytes, StandardCharsets.UTF_8);
        return "[truncated to last " + MAX_LOG_BYTES + " bytes of " + size + " byte log]\n" + tail;
    }

    private String labelFor(Path path, Workspace workspace) {
        if (path.startsWith(agentHome)) {
            return "agent-home/" + agentHome.relativize(path);
        }
        if (path.startsWith(workspace.root())) {
            return "workspace/" + workspace.root().relativize(path);
        }
        return path.getFileName().toString();
    }

    private static String sanitizeTitle(String title) {
        String normalized = Normalizer.normalize(blankFallback(title, "untitled"), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        String slug = normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.isBlank()) {
            return "untitled";
        }
        return slug.length() > 60 ? slug.substring(0, 60).replaceAll("-+$", "") : slug;
    }

    private static String blankFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private record LogAttachment(String label, Path path, String content) {}

    public record BugReportFile(String id, String relativePath, String absolutePath, String workspaceRoot, List<String> logFiles) {}
}
