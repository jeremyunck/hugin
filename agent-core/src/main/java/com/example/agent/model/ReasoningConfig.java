package com.example.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Reasoning controls for providers that support a unified reasoning object. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReasoningConfig(
        String effort,
        Boolean exclude,
        Boolean enabled
) {
    public static ReasoningConfig maxEffort() {
        return new ReasoningConfig("max", Boolean.FALSE, Boolean.TRUE);
    }
}
