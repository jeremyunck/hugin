package com.example.agent;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Thread-scoped carrier for the OpenRouter API key that should authenticate LLM calls made while
 * serving a particular user. An agent run executes start-to-finish on a single worker thread, so the
 * owning request sets the user's key here before invoking the agent and clears it in a finally block;
 * {@link OpenAiClient} reads it per request and falls back to the server-wide configured key when no
 * per-user key is bound. This keeps one user's key from ever being used for another user's traffic.
 */
@Component
public class OpenRouterKeyContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** Binds {@code apiKey} to the current thread; a blank/null value clears any existing binding. */
    public void set(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(apiKey);
        }
    }

    /** The key bound to the current thread, if any. */
    public Optional<String> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Removes any key bound to the current thread. Always call this in a finally block. */
    public void clear() {
        CURRENT.remove();
    }
}
