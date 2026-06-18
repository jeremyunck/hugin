package com.example.agent;

import com.example.agent.tool.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Discovers repo-local agent skills from conventional workspace folders.
 */
public final class WorkspaceSkills {

    private static final List<String> SKILL_ROOTS = List.of("skills", "docs/skills");
    private static final int MAX_SKILLS = 20;

    private WorkspaceSkills() {}

    public static List<SkillSummary> list(Workspace workspace) {
        Map<String, SkillSummary> skills = new LinkedHashMap<>();
        for (String rootName : SKILL_ROOTS) {
            Path root = workspace.root().resolve(rootName);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().equals("SKILL.md"))
                        .sorted(Comparator.comparing(Path::toString))
                        .limit(MAX_SKILLS)
                        .forEach(path -> {
                            SkillSummary summary = parseSkill(workspace, path);
                            skills.putIfAbsent(summary.path(), summary);
                        });
            } catch (IOException ignored) {
                // Best-effort discovery; unreadable skill folders should not break the agent.
            }
        }
        return new ArrayList<>(skills.values());
    }

    private static SkillSummary parseSkill(Workspace workspace, Path path) {
        String relative = workspace.relativize(path);
        String fallbackName = path.getParent() != null ? path.getParent().getFileName().toString() : "skill";
        String name = fallbackName;
        String description = "No description provided.";
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int first = firstNonBlankLine(lines);
            if (first != -1 && lines.get(first).trim().equals("---")) {
                int end = -1;
                for (int i = first + 1; i < lines.size(); i++) {
                    if (lines.get(i).trim().equals("---")) {
                        end = i;
                        break;
                    }
                }
                if (end != -1) {
                    Map<String, String> frontMatter = parseFrontMatter(lines.subList(first + 1, end));
                    name = frontMatter.getOrDefault("name", name);
                    description = frontMatter.getOrDefault("description", description);
                }
            }
        } catch (IOException ignored) {
            // Fall back to folder name.
        }
        return new SkillSummary(name, description, relative);
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

    private static int firstNonBlankLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                return i;
            }
        }
        return -1;
    }

    public record SkillSummary(String name, String description, String path) {}
}
