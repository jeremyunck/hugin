package com.example.agent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;

/**
 * Configuration for the built-in local tools (file access, search, shell).
 *
 * <ul>
 *   <li>{@code enabled} — master switch; when false no local tools are advertised.</li>
 *   <li>{@code workspaceRoot} — directory all file operations are confined to and the
 *       working directory for shell commands. Defaults to the process working directory.</li>
 *   <li>{@code bashTimeout} — wall-clock limit for a single shell command.</li>
 *   <li>{@code maxOutputChars} — cap on the size of any single tool result.</li>
 *   <li>{@code denyList} — glob patterns (relative to workspace root) that read, write, and
 *       edit are forbidden from accessing. Examples: {@code **.env}, {@code secrets/**}.</li>
 *   <li>{@code shell} — the shell executable {@code run_bash} invokes. Blank auto-detects the
 *       user's login shell from the {@code SHELL} environment variable, falling back to
 *       {@code /bin/sh}.</li>
 *   <li>{@code loginShell} — when true (default) {@code run_bash} runs the command through a
 *       login shell ({@code -l}) so the user's profile is sourced. This is what makes tools
 *       installed via Homebrew ({@code brew}, {@code /opt/homebrew/bin}, {@code /usr/local/bin})
 *       and other PATH entries set up in {@code ~/.zprofile} / {@code ~/.bash_profile} resolvable.
 *       The trade-off: the profile may define aliases/functions or run side-effecting startup code.
 *       Set this to false to run a plain non-login shell when that is a concern.</li>
 *   <li>{@code jitToolDirectory} — directory within each workspace where just-in-time tool
 *       manifests live. The agent rescans this directory on every loop iteration so tools created
 *       at runtime are available without restarting the service.</li>
 * </ul>
 */
@ConfigurationProperties("agent.tools")
public record LocalToolProperties(
        Boolean enabled,
        String workspaceRoot,
        Duration bashTimeout,
        Integer maxOutputChars,
        List<String> denyList,
        String shell,
        Boolean loginShell,
        String jitToolDirectory) {

    @ConstructorBinding
    public LocalToolProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            workspaceRoot = ".";
        }
        if (bashTimeout == null) {
            bashTimeout = Duration.ofSeconds(30);
        }
        if (maxOutputChars == null || maxOutputChars <= 0) {
            maxOutputChars = 30_000;
        }
        if (denyList == null) {
            denyList = List.of();
        }
        if (shell == null) {
            shell = "";
        }
        if (loginShell == null) {
            loginShell = true;
        }
        if (jitToolDirectory == null || jitToolDirectory.isBlank()) {
            jitToolDirectory = ".bouw/jit-tools";
        }
    }

    /** Backwards-compatible constructor without shell settings (auto-detect, login shell on). */
    public LocalToolProperties(
            Boolean enabled,
            String workspaceRoot,
            Duration bashTimeout,
            Integer maxOutputChars,
            List<String> denyList) {
        this(enabled, workspaceRoot, bashTimeout, maxOutputChars, denyList, null, null, null);
    }

    /** Backwards-compatible constructor that also accepts shell settings. */
    public LocalToolProperties(
            Boolean enabled,
            String workspaceRoot,
            Duration bashTimeout,
            Integer maxOutputChars,
            List<String> denyList,
            String shell,
            Boolean loginShell) {
        this(enabled, workspaceRoot, bashTimeout, maxOutputChars, denyList, shell, loginShell, null);
    }
}
