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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerSandboxManagerTest {

    @TempDir
    Path tmp;

    @Test
    void fallsBackToHostWorkspaceWhenDockerCliIsUnavailable() throws Exception {
        Path workspaceRoot = Files.createDirectory(tmp.resolve("default-workspace"));
        Workspace defaultWorkspace = new Workspace(new LocalToolProperties(
                true, workspaceRoot.toString(), Duration.ofSeconds(10), 30_000, List.of()));
        WorkspaceRegistry registry = new WorkspaceRegistry(defaultWorkspace);
        WorkspaceFactory factory = new WorkspaceFactory();
        SandboxProperties properties = new SandboxProperties(
                true,
                "ubuntu:24.04",
                "/definitely/missing/docker",
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                "",
                "bouw-sbx-",
                25);

        DockerSandboxManager manager = new DockerSandboxManager(properties, registry, factory, tmp.toString());

        SandboxInfo sandbox = manager.create(null);
        Path workspace = Path.of(sandbox.workspace());
        Path sandboxRoot = workspace.getParent();

        assertThat(sandbox.containerName()).startsWith("host-fallback-");
        assertThat(workspace).exists().isDirectory();
        assertThat(sandboxRoot).isNotNull();

        var result = manager.exec(sandbox.id(), "touch sandbox-smoke.txt", Duration.ofSeconds(10));

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(Files.exists(workspace.resolve("sandbox-smoke.txt"))).isTrue();

        manager.delete(sandbox.id());
        assertThat(sandboxRoot).doesNotExist();
    }

    @Test
    void doesNotFallBackWhenDockerStartupTimesOut() throws Exception {
        Path workspaceRoot = Files.createDirectory(tmp.resolve("default-workspace-timeout"));
        Workspace defaultWorkspace = new Workspace(new LocalToolProperties(
                true, workspaceRoot.toString(), Duration.ofSeconds(10), 30_000, List.of()));
        WorkspaceRegistry registry = new WorkspaceRegistry(defaultWorkspace);
        WorkspaceFactory factory = new WorkspaceFactory();
        Path fakeDocker = writeFakeDockerScript("sleep 2\n");
        SandboxProperties properties = new SandboxProperties(
                true,
                "ubuntu:24.04",
                fakeDocker.toString(),
                Duration.ofSeconds(10),
                Duration.ofMillis(200),
                "",
                "bouw-sbx-",
                25);

        DockerSandboxManager manager = new DockerSandboxManager(properties, registry, factory, tmp.toString());

        assertThatThrownBy(() -> manager.create(null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("docker run timed out");
        try (var sandboxes = Files.list(tmp.resolve("sandboxes"))) {
            assertThat(sandboxes.findAny()).isEmpty();
        }
    }

    private Path writeFakeDockerScript(String body) throws IOException {
        Path script = tmp.resolve("fake-docker.sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return script;
    }
}
