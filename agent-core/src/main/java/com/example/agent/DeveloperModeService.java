package com.example.agent;

import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks developer-mode state and applies the corresponding root log level.
 */
@Service
public class DeveloperModeService {

    private final LoggingSystem loggingSystem;
    private final AtomicBoolean enabled = new AtomicBoolean(false);

    public DeveloperModeService(LoggingSystem loggingSystem) {
        this.loggingSystem = loggingSystem;
    }

    public void setEnabled(boolean on) {
        enabled.set(on);
        loggingSystem.setLogLevel(null, on ? LogLevel.DEBUG : LogLevel.INFO);
    }

    public boolean isEnabled() {
        return enabled.get();
    }
}
