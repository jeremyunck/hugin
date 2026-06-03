package com.example.discord;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordBotServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void splitMessage_shortTextReturnsSingleChunk() {
        List<String> result = DiscordBotService.splitMessage("hello world", 2000);
        assertThat(result).containsExactly("hello world");
    }

    @Test
    void splitMessage_longTextSplitsOnNewline() {
        String line = "a".repeat(1500);
        String text = line + "\n" + line;
        List<String> result = DiscordBotService.splitMessage(text, 2000);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).hasSize(1500);
        assertThat(result.get(1)).hasSize(1500);
    }

    @Test
    void splitMessage_noNewlineInOversizedChunkSplitsAtLimit() {
        String text = "x".repeat(4500);
        List<String> result = DiscordBotService.splitMessage(text, 2000);
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).hasSize(2000);
        assertThat(result.get(1)).hasSize(2000);
        assertThat(result.get(2)).hasSize(500);
    }

    @Test
    void formatForDiscord_convertsMarkdownTableToBullets() {
        String input = """
                Intro

                | Catalyst | Why it matters |
                | --- | --- |
                | $100M CHIPS R&D LOI | Signals confidence |
                | Q1 2026 earnings beat | Shows growth |
                """;

        String output = DiscordBotService.formatForDiscord(input);

        assertThat(output).contains("Intro");
        assertThat(output).doesNotContain("| Catalyst | Why it matters |");
        assertThat(output).contains("**Catalyst / Why it matters**");
        assertThat(output).contains("- **$100M CHIPS R&D LOI** — Signals confidence");
        assertThat(output).contains("- **Q1 2026 earnings beat** — Shows growth");
    }

    @Test
    void formatForDiscord_leavesCodeBlocksUntouched() {
        String input = """
                ```text
                | Catalyst | Why it matters |
                | --- | --- |
                ```
                """;

        assertThat(DiscordBotService.formatForDiscord(input)).isEqualTo(input);
    }

    @Test
    void discordProperties_serverUrlStripsTrailingSlash() {
        DiscordProperties props = new DiscordProperties();
        props.setServerUrl("http://localhost:8080///");
        assertThat(props.getServerUrl()).isEqualTo("http://localhost:8080");
    }

    @Test
    void discordProperties_hasApiKeyFalseWhenBlank() {
        DiscordProperties props = new DiscordProperties();
        props.setApiKey("  ");
        assertThat(props.hasApiKey()).isFalse();
    }

    @Test
    void discordProperties_hasRoutingModelsOnlyWhenAllThreeConfigured() {
        DiscordProperties props = new DiscordProperties();
        assertThat(props.hasRoutingModels()).isFalse();

        props.setDecision("decision");
        props.setComplex("complex");
        props.setSimple("simple");
        assertThat(props.hasRoutingModels()).isTrue();
    }

    @Test
    void discordProperties_normalizesGithubRepoUrl() {
        DiscordProperties props = new DiscordProperties();
        props.setGithubRepo("https://github.com/jeremyunck/hugin.git/");
        assertThat(props.getGithubRepo()).isEqualTo("jeremyunck/hugin");
    }

    @Test
    void discordProperties_mentionOnlyDefaultsToTrue() {
        // By default Hugin stays quiet in guild channels unless @mentioned or replied to.
        assertThat(new DiscordProperties().isMentionOnly()).isTrue();
    }

    @Test
    void truncateWords_capsLongTextAtWordLimit() {
        String text = "one two three four five";
        assertThat(DiscordBotService.truncateWords(text, 3))
                .isEqualTo("one two three ...(truncated)");
    }

    @Test
    void tail_returnsLastLines() throws Exception {
        Path log = tempDir.resolve("hugin.log");
        Files.writeString(log, "a\nb\nc\nd\n");

        assertThat(DiscordBotService.tail(log, 2)).containsExactly("c", "d");
    }

    @Test
    void boundedLogLines_keepsNewestLinesWithinBudget() {
        List<String> result = DiscordBotService.boundedLogLines(
                List.of("old line", "middle line", "newest line"), 20);

        assertThat(result).containsExactly("...(older log lines omitted)", "newest line");
    }

    @Test
    void capBody_leavesBodyUnderLimitUnchanged() {
        String small = "Bug Report\n\nshort body";
        assertThat(DiscordBotService.capBody(small)).isSameAs(small);
    }

    @Test
    void capBody_truncatesOverLimitBodyBelowGithubLimit() {
        String oversized = "x".repeat(200_000);
        String capped = DiscordBotService.capBody(oversized);
        assertThat(capped.length()).isLessThanOrEqualTo(65_000);
        assertThat(capped).endsWith("(report truncated to fit GitHub's character limit)");
    }

    @Test
    void buildIssueBody_boundsLogsToRecentTurns() {
        DiscordBotService service = new DiscordBotService(
                new DiscordProperties(), null, null, tempDir);
        // Simulate a long-lived channel session that accumulated far more turns than the window.
        for (int i = 0; i < 500; i++) {
            service.logToolResult("session-long", "tool" + i, "ok");
        }

        String body = service.buildIssueBody("session-long", "Jeremy");

        // Body stays under GitHub's 65,536-character hard limit so issue creation won't 422.
        assertThat(body.length()).isLessThanOrEqualTo(65_000);
        // The sliding window keeps the newest turns and evicts the oldest.
        long toolLines = body.lines().filter(l -> l.startsWith("- Tool result")).count();
        assertThat(toolLines).isLessThanOrEqualTo(60);
        assertThat(body).contains("`tool499`");
        assertThat(body).doesNotContain("`tool0`");
    }

    @Test
    void buildIssueBody_includesToolDiagnosticsAndBoundedLogExcerpts() throws Exception {
        Files.writeString(tempDir.resolve("hugin.log"),
                "regular line\n" + "word ".repeat(220) + "\n");
        Files.writeString(tempDir.resolve("discord.log"), "discord line\n");

        DiscordBotService service = new DiscordBotService(
                new DiscordProperties(), null, null, tempDir);
        service.logToolCall("session-1", "list_mcp_servers", "{}");
        service.logToolResult("session-1", "list_mcp_servers", "Server: web-search");

        String body = service.buildIssueBody("session-1", "Jeremy");

        assertThat(body).contains("## Tool Calls");
        assertThat(body).contains("Tool call `list_mcp_servers` args: {}");
        assertThat(body).contains("Tool result `list_mcp_servers`: Server: web-search");
        assertThat(body).contains("## Hugin Server Log");
        assertThat(body).contains("## Discord Bot Log");
        assertThat(body).contains("each line truncated to 200 words");
        assertThat(body).contains("...(truncated)");
    }
}
