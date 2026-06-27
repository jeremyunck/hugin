package com.example.integration.service;

import com.example.agent.model.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

class BugReportServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BugReportCatalogService bugReportCatalog = mock(BugReportCatalogService.class);

    @TempDir
    Path tempDir;

    BugReportServiceTest() {
        lenient().when(bugReportCatalog.save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.empty());
    }

    @Test
    void writesBugReportIntoWorkspaceWithDateAndTitle() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace"));
        Path agentHome = Files.createDirectories(tempDir.resolve("agent-home"));
        Files.createDirectories(agentHome.resolve("logs"));
        Files.writeString(agentHome.resolve("logs/bouw.log"), "server log");

        var workspace = mock(com.example.agent.tool.Workspace.class);
        when(workspace.root()).thenReturn(workspaceRoot);
        when(workspace.resolve("bug-reports/2026-06-18/2026-06-18_090506-chat-hung-on-tool-result.txt"))
                .thenReturn(workspaceRoot.resolve("bug-reports/2026-06-18/2026-06-18_090506-chat-hung-on-tool-result.txt"));
        when(workspace.relativize(workspaceRoot.resolve("bug-reports/2026-06-18/2026-06-18_090506-chat-hung-on-tool-result.txt")))
                .thenReturn("bug-reports/2026-06-18/2026-06-18_090506-chat-hung-on-tool-result.txt");
        Clock clock = Clock.fixed(Instant.parse("2026-06-18T14:05:06Z"), ZoneId.of("America/Chicago"));
        when(bugReportCatalog.save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(new BugReportCatalogService.StoredBugReport(
                        "bug-1", "Chat hung on tool result", "session-1", null, null,
                        "bug-reports/2026-06-18/2026-06-18_090506-chat-hung-on-tool-result.txt",
                        "body", "2026-06-18T14:05:06Z")));
        BugReportService service = new BugReportService(objectMapper, bugReportCatalog, workspace, agentHome, clock);

        var result = service.writeReport(
                "session-1",
                "Chat hung on tool result",
                "owner-1",
                null,
                null,
                List.of(ChatMessage.user("hello"), ChatMessage.assistant("hi")),
                JsonNodeFactory.instance.objectNode().put("title", "local thread"),
                JsonNodeFactory.instance.objectNode().put("screen", "purechat"));

        Path saved = workspaceRoot.resolve(result.relativePath());
        assertThat(saved).exists();
        assertThat(result.relativePath()).contains("bug-reports/2026-06-18/");
        assertThat(result.relativePath()).contains("chat-hung-on-tool-result.txt");
        assertThat(result.logFiles()).contains("agent-home/logs/bouw.log");

        String body = Files.readString(saved);
        assertThat(body).contains("Bouw Bug Report");
        assertThat(body).contains("Client Thread Snapshot");
        assertThat(body).contains("Server Conversation History");
        assertThat(body).contains("server log");
    }

    @Test
    void skipsMissingLogsAndFallsBackToUntitledSlug() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-2"));
        Path agentHome = Files.createDirectories(tempDir.resolve("agent-home-2"));

        var workspace = mock(com.example.agent.tool.Workspace.class);
        when(workspace.root()).thenReturn(workspaceRoot);
        when(workspace.resolve("bug-reports/2026-06-18/2026-06-18_000000-untitled.txt"))
                .thenReturn(workspaceRoot.resolve("bug-reports/2026-06-18/2026-06-18_000000-untitled.txt"));
        when(workspace.relativize(workspaceRoot.resolve("bug-reports/2026-06-18/2026-06-18_000000-untitled.txt")))
                .thenReturn("bug-reports/2026-06-18/2026-06-18_000000-untitled.txt");
        Clock clock = Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC"));
        BugReportService service = new BugReportService(objectMapper, bugReportCatalog, workspace, agentHome, clock);

        var result = service.writeReport(
                "session-2",
                "???",
                "owner-2",
                null,
                null,
                List.of(),
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode());

        Path saved = workspaceRoot.resolve(result.relativePath());
        assertThat(saved).exists();
        assertThat(result.relativePath()).contains("untitled.txt");
        assertThat(result.logFiles()).isEmpty();
        assertThat(Files.readString(saved)).contains("No configured log files were found.");
    }

    @Test
    void truncatesLargeLogsToRecentTail() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace-3"));
        Path agentHome = Files.createDirectories(tempDir.resolve("agent-home-3"));
        Files.createDirectories(agentHome.resolve("logs"));
        Files.writeString(agentHome.resolve("logs/bouw.log"), "a".repeat(300_000) + "\nTAIL");

        var workspace = mock(com.example.agent.tool.Workspace.class);
        when(workspace.root()).thenReturn(workspaceRoot);
        when(workspace.resolve("bug-reports/2026-06-18/2026-06-18_000000-large-log.txt"))
                .thenReturn(workspaceRoot.resolve("bug-reports/2026-06-18/2026-06-18_000000-large-log.txt"));
        when(workspace.relativize(workspaceRoot.resolve("bug-reports/2026-06-18/2026-06-18_000000-large-log.txt")))
                .thenReturn("bug-reports/2026-06-18/2026-06-18_000000-large-log.txt");
        Clock clock = Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC"));
        BugReportService service = new BugReportService(objectMapper, bugReportCatalog, workspace, agentHome, clock);

        var result = service.writeReport(
                "session-3",
                "large log",
                "owner-3",
                null,
                null,
                List.of(),
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode());

        String body = Files.readString(workspaceRoot.resolve(result.relativePath()));
        assertThat(body).contains("[truncated to last");
        assertThat(body).contains("TAIL");
    }
}
