package com.example.agent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** OpenAI-format tool definition sent to the model. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(String type, FunctionDefinition function) {

    public record FunctionDefinition(
            String name,
            String description,
            Map<String, Object> parameters
    ) {}

    public static ToolDefinition from(String name, String description, Map<String, Object> parameters) {
        return new ToolDefinition("function", new FunctionDefinition(name, description, parameters));
    }

    public static ToolDefinition from(AvailableTool tool) {
        return from(tool.name(), tool.description(), tool.inputSchema());
    }
}
