package com.example.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provides access to Hugin's bundled skill files, packaged as classpath resources at
 * {@code bundled-skills/<relative-path>} inside the backend jar.
 *
 * <p>In GitHub project sandbox mode, bundled skills are written into the container's repository
 * workspace so the agent can read them via {@code read_file}, and extracted to the per-sandbox
 * placeholder workspace so {@link WorkspaceSkills} can list them for the initial system prompt.
 */
public final class BundledSkills {

    private static final String CLASSPATH_BASE = "bundled-skills/";

    /** Workspace-relative paths for all bundled {@code SKILL.md} files. */
    public static final List<String> SKILL_PATHS = List.of(
            "skills/explore-github-repository/SKILL.md",
            "docs/skills/github-prs/SKILL.md",
            "docs/skills/google-docs-sheets/SKILL.md",
            "docs/skills/google-gmail/SKILL.md",
            "docs/skills/hugin-bug-reports/SKILL.md",
            "docs/skills/hugin-local-dev/SKILL.md",
            "docs/skills/hugin-sandbox-screenshots/SKILL.md",
            "docs/skills/hugin-ui-screenshots/SKILL.md"
    );

    private BundledSkills() {}

    /**
     * Returns summaries for all bundled skills, parsed from their front-matter. Each summary's
     * {@code path} is the workspace-relative path the agent should pass to {@code read_file}.
     */
    public static List<WorkspaceSkills.SkillSummary> list() {
        List<WorkspaceSkills.SkillSummary> result = new ArrayList<>();
        for (String relativePath : SKILL_PATHS) {
            String content = readContent(relativePath);
            if (content != null) {
                result.add(parseSkill(relativePath, content));
            }
        }
        return result;
    }

    /**
     * Reads a bundled skill's content by its workspace-relative path (e.g.
     * {@code skills/explore-github-repository/SKILL.md}). Returns {@code null} if the resource
     * is absent from the classpath.
     */
    public static String readContent(String skillPath) {
        try (InputStream in = BundledSkills.class.getClassLoader()
                .getResourceAsStream(CLASSPATH_BASE + skillPath)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Extracts all bundled skill files into {@code targetDir}, writing each file at its
     * conventional relative path (e.g. {@code targetDir/skills/explore-github-repository/SKILL.md}).
     * Existing files are overwritten. Missing classpath resources are silently skipped.
     */
    public static void extractTo(Path targetDir) throws IOException {
        for (String relativePath : SKILL_PATHS) {
            String content = readContent(relativePath);
            if (content == null) {
                continue;
            }
            Path target = targetDir.resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private static WorkspaceSkills.SkillSummary parseSkill(String relativePath, String content) {
        String name = folderOf(relativePath);
        String description = "No description provided.";
        List<String> lines = content.lines().toList();
        int first = firstNonBlankLine(lines);
        if (first != -1 && "---".equals(lines.get(first).trim())) {
            int end = -1;
            for (int i = first + 1; i < lines.size(); i++) {
                if ("---".equals(lines.get(i).trim())) {
                    end = i;
                    break;
                }
            }
            if (end != -1) {
                Map<String, String> fm = parseFrontMatter(lines.subList(first + 1, end));
                name = fm.getOrDefault("name", name);
                description = fm.getOrDefault("description", description);
            }
        }
        return new WorkspaceSkills.SkillSummary(name, description, relativePath);
    }

    /** Returns the name of the folder directly containing the {@code SKILL.md}. */
    private static String folderOf(String relativePath) {
        String normalised = relativePath.replace('\\', '/');
        int last = normalised.lastIndexOf('/');
        if (last <= 0) {
            return normalised;
        }
        int secondLast = normalised.lastIndexOf('/', last - 1);
        return normalised.substring(secondLast + 1, last);
    }

    private static int firstNonBlankLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, String> parseFrontMatter(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }
}
