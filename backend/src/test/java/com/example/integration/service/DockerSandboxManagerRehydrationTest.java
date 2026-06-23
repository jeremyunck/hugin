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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that a GitHub project workspace is rehydrated from its persisted binding after the
 * in-memory registration is lost (e.g. a server restart), so a resumed chat resolves to its cloned
 * repository instead of the default host workspace.
 */
class DockerSandboxManagerRehydrationTest {

    @TempDir
    Path tmp;

    @Test
    void resolvesClonedWorkspaceAfterRestartViaRehydration() throws Exception {
        Path defaultWorkspaceDir = Files.createDirectory(tmp.resolve("default-workspace"));
        InMemoryGitHubWorkspaceStore store = new InMemoryGitHubWorkspaceStore();
        Path origin = initRepository(tmp.resolve("origin"));

        // First boot: create the GitHub project workspace.
        WorkspaceRegistry firstRegistry = newRegistry(defaultWorkspaceDir);
        DockerSandboxManager firstBoot = newManager(firstRegistry, store);
        SandboxInfo sandbox = firstBoot.createGitHubRepoSandbox(
                null, origin.toUri().toString(), "octo/origin", "main", null, null);

        Path clone = Path.of(sandbox.workspace());
        assertThat(clone).isDirectory();
        assertThat(clone.resolve(".git")).isDirectory();
        assertThat(firstRegistry.resolve(sandbox.id()).root()).isEqualTo(clone);
        assertThat(store.records).containsKey(sandbox.id());

        // Simulate a restart: brand-new registry + manager (empty in-memory state) sharing the store
        // and the same agent home, so the persisted clone is still on disk.
        WorkspaceRegistry secondRegistry = newRegistry(defaultWorkspaceDir);
        DockerSandboxManager secondBoot = newManager(secondRegistry, store);

        // Resolving the chat's workspace must rehydrate it to the clone, not fall back to the default.
        Workspace resolved = secondRegistry.resolve(sandbox.id());
        assertThat(resolved.root()).isEqualTo(clone);
        assertThat(secondRegistry.githubRepo(sandbox.id())).contains("octo/origin");
        assertThat(secondBoot.get(sandbox.id())).isPresent();

        // Deleting the chat removes the clone and its persisted binding.
        secondBoot.delete(sandbox.id());
        assertThat(clone).doesNotExist();
        assertThat(store.records).doesNotContainKey(sandbox.id());
    }

    private WorkspaceRegistry newRegistry(Path defaultWorkspaceDir) {
        Workspace defaultWorkspace = new Workspace(new LocalToolProperties(
                true, defaultWorkspaceDir.toString(), Duration.ofSeconds(10), 30_000, List.of()));
        return new WorkspaceRegistry(defaultWorkspace);
    }

    private DockerSandboxManager newManager(WorkspaceRegistry registry, GitHubWorkspaceStore store) {
        SandboxProperties properties = new SandboxProperties(
                true, "ubuntu:24.04", "/definitely/missing/docker",
                Duration.ofSeconds(10), Duration.ofSeconds(10), "", "hugin-sbx-", 25);
        DockerSandboxManager manager = new DockerSandboxManager(
                properties, registry, new WorkspaceFactory(), Optional.of(store), Optional.empty(), tmp.toString());
        registry.setRehydrator(manager);
        return manager;
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

    /** Minimal store that survives the simulated restart by living outside the manager instances. */
    private static final class InMemoryGitHubWorkspaceStore implements GitHubWorkspaceStore {
        private final Map<String, Record> records = new HashMap<>();

        @Override
        public void save(Record record) {
            records.put(record.sandboxId(), record);
        }

        @Override
        public Optional<Record> find(String sandboxId) {
            return Optional.ofNullable(records.get(sandboxId));
        }

        @Override
        public void delete(String sandboxId) {
            records.remove(sandboxId);
        }
    }
}
