package com.example.integration.service;

import com.example.agent.model.SandboxInfo;
import com.example.agent.tool.LocalToolProperties;
import com.example.agent.tool.Workspace;
import com.example.agent.tool.WorkspaceFactory;
import com.example.agent.tool.WorkspaceRegistry;
import com.example.integration.sandbox.SandboxProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DockerSandboxManagerTest {

    @TempDir
    Path tmp;

    @Test
    void fallsBackToHostWorkspaceWhenDockerCliIsUnavailable() throws Exception {
        Workspace defaultWorkspace = new Workspace(new LocalToolProperties(
                true, tmp.toString(), Duration.ofSeconds(10), 30_000, List.of()));
        WorkspaceRegistry registry = new WorkspaceRegistry(defaultWorkspace);
        WorkspaceFactory factory = new WorkspaceFactory();
        SandboxProperties properties = new SandboxProperties(
                true,
                "ubuntu:24.04",
                "/definitely/missing/docker",
                Duration.ofSeconds(10),
                Duration.ofSeconds(10),
                "",
                "hugin-sbx-",
                25);

        DockerSandboxManager manager = new DockerSandboxManager(properties, registry, factory, tmp.toString());

        SandboxInfo sandbox = manager.create(null);

        assertThat(sandbox.containerName()).startsWith("host-fallback-");
        assertThat(Path.of(sandbox.workspace())).exists().isDirectory();

        var result = manager.exec(sandbox.id(), "touch sandbox-smoke.txt", Duration.ofSeconds(10));

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(Files.exists(Path.of(sandbox.workspace()).resolve("sandbox-smoke.txt"))).isTrue();

        manager.delete(sandbox.id());
        assertThat(Path.of(sandbox.workspace()).getParent()).doesNotExist();
    }
}
