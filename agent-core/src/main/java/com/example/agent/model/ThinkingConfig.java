package com.example.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** DeepSeek thinking-mode toggle for providers that support it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ThinkingConfig(@JsonProperty("type") String type) {

    public static ThinkingConfig enabled() {
        return new ThinkingConfig("enabled");
    }
}
