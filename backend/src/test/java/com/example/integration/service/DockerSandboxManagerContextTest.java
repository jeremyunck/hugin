package com.example.integration.service;

import com.example.agent.tool.LocalToolProperties;
import com.example.agent.tool.Workspace;
import com.example.agent.tool.WorkspaceFactory;
import com.example.agent.tool.WorkspaceRegistry;
import com.example.integration.sandbox.SandboxProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for a production startup failure: Spring could not instantiate
 * {@link DockerSandboxManager} because the class declared two public constructors and neither was
 * annotated {@code @Autowired}. Faced with an ambiguous choice, Spring fell back to looking for a
 * no-arg constructor that does not exist, failing the
 * {@code LocalToolRegistry -> BashCommandTool -> DockerSandboxManager} bean chain with:
 *
 * <pre>NoSuchMethodException: DockerSandboxManager.&lt;init&gt;()</pre>
 *
 * <p>This test registers {@code DockerSandboxManager} by type (no explicit constructor arguments),
 * which drives Spring's real constructor-resolution and autowiring path — the same path that broke
 * in production — and asserts the context starts and the bean is created. With the ambiguous
 * constructors and no {@code @Autowired} marker this fails; with the autowired constructor it passes.
 */
class DockerSandboxManagerContextTest {

    @TempDir
    Path tmp;

    @Test
    void dockerSandboxManagerIsInstantiableBySpring() {
        new ApplicationContextRunner()
                .withBean(WorkspaceFactory.class)
                .withBean(WorkspaceRegistry.class, () -> new WorkspaceRegistry(
                        new Workspace(new LocalToolProperties(
                                true, tmp.toString(), Duration.ofSeconds(10), 30_000, List.of()))))
                .withBean(SandboxProperties.class, () -> new SandboxProperties(
                        true, "ubuntu:24.04", "docker",
                        Duration.ofSeconds(10), Duration.ofSeconds(10), "", "bouw-sbx-", 25))
                // Registered by type so Spring must pick a constructor and autowire it itself,
                // exactly as it does during application startup.
                .withBean(DockerSandboxManager.class)
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(DockerSandboxManager.class));
    }
}
