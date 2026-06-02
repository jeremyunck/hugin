package com.example.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReadDiscordChannelToolTest {

    private final ReadDiscordChannelTool tool = new ReadDiscordChannelTool();

    @Test
    void reportsWhenNoChannelHistoryAvailable() {
        assertThat(tool.execute(Map.of(), new ToolContext(null)))
                .contains("No Discord channel history");
        assertThat(tool.execute(Map.of(), new ToolContext(null, "discord-channel-1", List.of())))
                .contains("No Discord channel history");
    }

    @Test
    void returnsAllMessagesOldestFirstByDefault() {
        var ctx = new ToolContext(null, "discord-channel-1", List.of("Alice: 1", "Bob: 2", "Cara: 3"));
        String out = tool.execute(Map.of(), ctx);
        assertThat(out).contains("Alice: 1").contains("Bob: 2").contains("Cara: 3");
        assertThat(out.indexOf("Alice: 1")).isLessThan(out.indexOf("Cara: 3"));
    }

    @Test
    void returnsOnlyTheMostRecentMessagesWhenCountGiven() {
        var ctx = new ToolContext(null, "discord-channel-1",
                List.of("Alice: 1", "Bob: 2", "Cara: 3", "Dan: 4"));
        String out = tool.execute(Map.of("count", 2), ctx);
        assertThat(out).contains("Cara: 3").contains("Dan: 4")
                .doesNotContain("Alice: 1").doesNotContain("Bob: 2");
    }

    @Test
    void cappedAtTenAndTreatsNonPositiveCountAsDefault() {
        List<String> twelve = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) twelve.add("U: " + i);
        var ctx = new ToolContext(null, "discord-channel-1", twelve);

        // count above the cap returns at most the last 10
        String capped = tool.execute(Map.of("count", 50), ctx);
        assertThat(capped).contains("U: 12").contains("U: 3").doesNotContain("U: 2");

        // a non-positive count falls back to the default (10)
        String zero = tool.execute(Map.of("count", 0), ctx);
        assertThat(zero).contains("U: 12").contains("U: 3").doesNotContain("U: 2");
    }
}
