package com.example.agent.tool;

import com.example.agent.StartupAnnouncementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code hugin update}, which pulls {@code origin/main}, rebuilds the jars,
 * and restarts the local services using the checked-out repository.
 */
@Component
public class SelfUpdateTool implements LocalTool {

    private static final Logger log = LoggerFactory.getLogger(SelfUpdateTool.class);

    private final Workspace workspace;
    private final Duration timeout;
    private final int maxChars;
    private final Optional<StartupAnnouncementService> announcement;

    private static final Duration MIN_TIMEOUT = Duration.ofMinutes(10);

    public SelfUpdateTool(Workspace workspace, LocalToolProperties properties,
                          Optional<StartupAnnouncementService> announcement) {
        this.workspace = workspace;
        // Maven builds take several minutes; use at least 10 minutes regardless of bash-timeout.
        Duration configured = properties.bashTimeout();
        this.timeout = configured.compareTo(MIN_TIMEOUT) < 0 ? MIN_TIMEOUT : configured;
        this.maxChars = properties.maxOutputChars();
        this.announcement = announcement;
    }

    @Override
    public String name() {
        return "self_update";
    }

    @Override
    public String description() {
        return "Update Hugin agent from the latest main branch. No arguments needed";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Collections.emptyMap(),
                "required", Collections.emptyList());
    }

    @Override
    public String execute(Map<String, Object> arguments) throws IOException, InterruptedException {
        return execute(arguments, new ToolContext(workspace));
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws IOException, InterruptedException {
        // Run through bash so PATH is resolved even when the JVM was started with a minimal
        // environment (e.g. macOS LaunchAgent). Prepend common Homebrew / user-local bins.
        String currentPath = System.getenv("PATH") != null ? System.getenv("PATH") : "";
        String extendedPath = "/opt/homebrew/bin:/usr/local/bin:/opt/local/bin:" + currentPath;

        ProcessBuilder builder = new ProcessBuilder(
                "/bin/bash", "-c", "hugin update");
        builder.directory(ctx.workspace().root().toFile());
        builder.environment().put("PATH", extendedPath);
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
            return "Error: 'hugin update' timed out after " + timeout.toSeconds() + "s.\n"
                    + "Partial output:\n" + render(output);
        }
        reader.join(2000);

        int exitCode = process.exitValue();
        String rendered = render(output);
        if (exitCode == 0) {
            announcement.ifPresent(svc -> svc.set(buildAnnouncement(ctx.workspace().root())));
        }
        return "exit code: " + exitCode + (rendered.isBlank() ? " (no output)" : "\n" + rendered);
    }

    private static String buildAnnouncement(Path workspaceRoot) {
        String version = resolveVersion(workspaceRoot);
        return "Self-update completed successfully. Now running version: " + version;
    }

    static String resolveVersion(Path root) {
        // Prefer a human-readable git description (tag + offset + hash).
        try {
            Process p = new ProcessBuilder("git", "describe", "--tags", "--always")
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.debug("git describe timed out in {}", root);
            } else {
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
                if (p.exitValue() == 0 && !out.isBlank()) {
                    return out;
                }
            }
        } catch (Exception e) {
            log.debug("git describe failed: {}", e.getMessage());
        }

        // Fall back to the project's own <version> in the root pom.xml.
        // Parse with DOM and look for <version> as a direct child of <project> so
        // dependency, plugin, and parent versions nested at deeper levels are ignored.
        try {
            Path pom = root.resolve("pom.xml");
            if (pom.toFile().exists()) {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setExpandEntityReferences(false);
                Document doc = factory.newDocumentBuilder().parse(pom.toFile());
                NodeList children = doc.getDocumentElement().getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node child = children.item(i);
                    if (child.getNodeType() == Node.ELEMENT_NODE
                            && "version".equals(child.getNodeName())) {
                        String v = child.getTextContent().trim();
                        if (!v.isBlank()) {
                            return v;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse pom.xml for version: {}", e.getMessage());
        }

        return "unknown";
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
}
