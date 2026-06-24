package com.example.agent.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Image attachment carried with a user message.
 *
 * <p>The wire format is camelCase ({@code mimeType}, {@code dataUrl}) to match the rest of the
 * chat API and the frontend client. The snake_case {@code mime_type} / {@code data_url} names are
 * still accepted on input via {@link JsonAlias} so attachments persisted by older builds (which
 * serialized those keys into the session event log) continue to deserialize.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatAttachment(
        String name,
        @JsonAlias("mime_type") String mimeType,
        @JsonAlias("data_url") String dataUrl,
        Long size
) {}
