package com.example.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Collects a {@link SystemFacts} snapshot for this JVM process and host machine. */
public final class EnvironmentProbe {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentProbe.class);

    private static final String[] TOOLCHAINS = {"git", "node", "python3", "uvx", "docker", "mvn"};

    private EnvironmentProbe() {}

    /**
     * Probes the current environment. {@code diskCheckPath} is the directory whose free-space
     * is reported (typically {@code $AGENT_HOME}).
     */
    public static SystemFacts probe(Path diskCheckPath) {
        long totalMemory = 0;
        long freeMemory  = 0;
        try {
            var mxBean = (com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();
            totalMemory = mxBean.getTotalMemorySize();
            freeMemory  = mxBean.getFreeMemorySize();
        } catch (Exception e) {
            log.debug("OperatingSystemMXBean unavailable, falling back to Runtime: {}", e.getMessage());
            Runtime rt  = Runtime.getRuntime();
            totalMemory = rt.maxMemory();
            freeMemory  = rt.freeMemory();
        }

        long freeDisk = 0;
        try {
            Path check = diskCheckPath.toAbsolutePath();
            if (!Files.exists(check)) {
                check = check.getRoot();
            }
            freeDisk = Files.getFileStore(check).getUsableSpace();
        } catch (Exception e) {
            log.debug("Could not read disk stats for {}: {}", diskCheckPath, e.getMessage());
        }

        Map<String, Boolean> toolchains = new LinkedHashMap<>();
        for (String cmd : TOOLCHAINS) {
            toolchains.put(cmd, isOnPath(cmd));
        }

        return new SystemFacts(
                System.getProperty("os.name", "unknown"),
                System.getProperty("os.version", "unknown"),
                System.getProperty("os.arch", "unknown"),
                Runtime.getRuntime().availableProcessors(),
                totalMemory,
                freeMemory,
                Runtime.getRuntime().maxMemory(),
                freeDisk,
                System.getProperty("java.version", "unknown"),
                toolchains);
    }

    private static boolean isOnPath(String cmd) {
        try {
            return new ProcessBuilder("which", cmd)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
