package com.example.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordBotServiceTest {

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
}
