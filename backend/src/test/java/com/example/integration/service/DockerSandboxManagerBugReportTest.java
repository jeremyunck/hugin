package com.example.integration.service;

import com.example.agent.model.SandboxInfo;
import com.example.agent.tool.LocalToolProperties;
import com.example.agent.tool.Workspace;
import com.example.agent.tool.WorkspaceFactory;
import com.example.agent.tool.WorkspaceRegistry;
import com.example.integration.sandbox.SandboxProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerSandboxManagerBugReportTest {

    @TempDir
    Path tmp;

    @Test
    void stagesSelectedBugReportIntoClonedRepository() throws Exception {
        Path defaultWorkspace = Files.createDirectory(tmp.resolve("default-workspace"));
        Workspace workspace = new Workspace(new LocalToolProperties(
                true, defaultWorkspace.toString(), Duration.ofSeconds(10), 30_000, List.of()));
        WorkspaceRegistry registry = new WorkspaceRegistry(workspace);
        WorkspaceFactory factory = new WorkspaceFactory();
        Path fakeDocker = writeFakeDockerUnavailableScript();
        SandboxProperties properties = new SandboxProperties(
                true,
                "ubuntu:24.04",
                fakeDocker.toString(),
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                "",
                "hugin-sbx-",
                25);
        DockerSandboxManager manager = new DockerSandboxManager(properties, registry, factory, tmp.toString());

        Path origin = initRepository(tmp.resolve("origin"));
        var bugReport = new BugReportCatalogService.StoredBugReport(
                "bug-1",
                "Hung chat",
                "session-1",
                null,
                null,
                "bug-reports/2026-06-18/hung-chat.txt",
                "Hugin Bug Report\n\nBody",
                "2026-06-18T14:05:06Z");

        SandboxInfo sandbox = manager.createGitHubRepoSandbox(
                null,
                origin.toUri().toString(),
                "origin",
                "main",
                "token-123",
                bugReport);

        assertThat(sandbox.containerName()).startsWith("host-fallback-");
        Path clonedReport = Path.of(sandbox.workspace())
                .resolve("origin")
                .resolve("bug-reports/2026-06-18/hung-chat.txt");
        assertThat(clonedReport).exists();
        assertThat(Files.readString(clonedReport)).contains("Hugin Bug Report");
    }

    private Path writeFakeDockerUnavailableScript() throws IOException {
        Path script = tmp.resolve("fake-docker-unavailable.sh");
        Files.writeString(script, "#!/bin/sh\nexit 127\n");
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }

    private Path initRepository(Path repo) throws Exception {
        Files.createDirectories(repo);
        run(repo, "git", "init", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "# test\n");
        run(repo, "git", "add", "README.md");
        run(repo, "git", "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "init");
        return repo;
    }

    private void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        assertThat(process.waitFor()).isZero();
    }
}
