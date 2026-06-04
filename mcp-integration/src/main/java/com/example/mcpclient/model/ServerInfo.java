package com.example.mcpclient.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ServerInfo(
        String name,
        McpServerDefinition definition,
        boolean connected,
        String error,
        List<ToolInfo> tools
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolInfo(String name, String description) {}
}
