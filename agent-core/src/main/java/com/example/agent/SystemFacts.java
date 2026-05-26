package com.example.agent;

import java.util.Map;

/** Point-in-time snapshot of the machine's capabilities. */
public record SystemFacts(
        String osName,
        String osVersion,
        String arch,
        int availableProcessors,
        long totalMemoryBytes,
        long freeMemoryBytes,
        long maxHeapBytes,
        long freeDiskBytes,
        String javaVersion,
        Map<String, Boolean> toolchains) {

    /** Compact multi-line summary injected as a system message on every agent request. */
    public String summary() {
        long totalMb = totalMemoryBytes / (1024 * 1024);
        long freeGb  = freeDiskBytes   / (1024 * 1024 * 1024);
        StringBuilder sb = new StringBuilder("System facts (this machine):\n");
        sb.append("OS: ").append(osName).append(' ').append(osVersion)
          .append(" (").append(arch).append(")\n");
        sb.append("CPU: ").append(availableProcessors).append(" cores\n");
        sb.append("RAM: ").append(totalMb).append(" MB\n");
        sb.append("Disk free: ").append(freeGb).append(" GB\n");
        sb.append("Java: ").append(javaVersion).append("\n");
        sb.append("Toolchains:");
        toolchains.forEach((tool, present) ->
                sb.append("\n  ").append(tool).append(": ").append(present ? "present" : "absent"));
        return sb.toString();
    }
}
