package com.example.agent.scheduler;

/**
 * Configuration for the self-scheduling feature (the {@code schedule_prompt} tool and the service
 * that runs scheduled prompts).
 *
 * <ul>
 *   <li>{@code enabled} — master switch; when false the scheduling tools are not registered and
 *       no scheduled prompts run.</li>
 *   <li>{@code storeFile} — path to the JSON file scheduled prompts are persisted to so they
 *       survive a restart. Supports {@code ~/}-relative paths.</li>
 *   <li>{@code maxPerTarget} — cap on how many active schedules a single origin session may hold,
 *       to bound runaway scheduling.</li>
 *   <li>{@code defaultZone} — IANA timezone used when a schedule request omits one.</li>
 * </ul>
 */
@org.springframework.boot.context.properties.ConfigurationProperties("agent.scheduler")
public record SchedulerProperties(
        Boolean enabled,
        String storeFile,
        Integer maxPerTarget,
        String defaultZone) {

    public SchedulerProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (storeFile == null || storeFile.isBlank()) {
            storeFile = "~/.bouw/scheduled-prompts.json";
        }
        if (maxPerTarget == null || maxPerTarget <= 0) {
            maxPerTarget = 20;
        }
        if (defaultZone == null || defaultZone.isBlank()) {
            defaultZone = java.time.ZoneId.systemDefault().getId();
        }
    }
}
