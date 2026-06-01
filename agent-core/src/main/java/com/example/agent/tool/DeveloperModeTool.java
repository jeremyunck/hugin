package com.example.agent.tool;

import com.example.agent.DeveloperModeService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Toggles developer mode: DEBUG logging and Discord tool-call visibility. */
@Component
public class DeveloperModeTool implements LocalTool {

    private final DeveloperModeService developerModeService;

    public DeveloperModeTool(DeveloperModeService developerModeService) {
        this.developerModeService = developerModeService;
    }

    @Override
    public String name() {
        return "set_developer_mode";
    }

    @Override
    public String description() {
        return "Switches developer mode on or off. When enabled: sets root logging to DEBUG level "
                + "and makes the Discord bot post all tool calls and their results inline in chat. "
                + "When disabled: reverts to INFO logging and hides tool calls from Discord chat.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "enabled", Map.of(
                                "type", "boolean",
                                "description", "true to turn on developer mode, false to turn it off")),
                "required", List.of("enabled"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        boolean on = optionalBoolean(arguments, "enabled", false);
        developerModeService.setEnabled(on);
        return on
                ? "Developer mode ON — root log level set to DEBUG; tool calls will be posted to Discord."
                : "Developer mode OFF — root log level set to INFO; tool calls hidden from Discord.";
    }
}
