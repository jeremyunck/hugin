package com.example.agent.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAttachmentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesCamelCaseFromFrontend() throws Exception {
        // The frontend (and the rest of the chat API) sends camelCase keys. Binding these is what
        // carries the actual image bytes (dataUrl) to the backend; a mismatch leaves it null.
        String json = "{\"name\":\"bird.png\",\"mimeType\":\"image/png\","
                + "\"dataUrl\":\"data:image/png;base64,abc123\",\"size\":123}";

        ChatAttachment attachment = objectMapper.readValue(json, ChatAttachment.class);

        assertThat(attachment.name()).isEqualTo("bird.png");
        assertThat(attachment.mimeType()).isEqualTo("image/png");
        assertThat(attachment.dataUrl()).isEqualTo("data:image/png;base64,abc123");
        assertThat(attachment.size()).isEqualTo(123L);
    }

    @Test
    void stillDeserializesLegacySnakeCase() throws Exception {
        // Attachments persisted by older builds used snake_case keys; they must still load.
        String json = "{\"name\":\"bird.png\",\"mime_type\":\"image/png\","
                + "\"data_url\":\"data:image/png;base64,abc123\",\"size\":123}";

        ChatAttachment attachment = objectMapper.readValue(json, ChatAttachment.class);

        assertThat(attachment.mimeType()).isEqualTo("image/png");
        assertThat(attachment.dataUrl()).isEqualTo("data:image/png;base64,abc123");
    }

    @Test
    void serializesCamelCase() throws Exception {
        String json = objectMapper.writeValueAsString(
                new ChatAttachment("bird.png", "image/png", "data:image/png;base64,abc123", 123L));

        assertThat(json).contains("\"mimeType\":\"image/png\"");
        assertThat(json).contains("\"dataUrl\":\"data:image/png;base64,abc123\"");
        assertThat(json).doesNotContain("mime_type");
        assertThat(json).doesNotContain("data_url");
    }
}
