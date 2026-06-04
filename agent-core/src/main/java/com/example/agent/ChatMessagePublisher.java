package com.example.agent;

import java.util.Optional;

/**
 * Sends a short text message back out to an external channel.
 *
 * <p>The concrete transport is provided by an integration module (for example Discord).
 */
public interface ChatMessagePublisher {

    /**
     * Delivers {@code message} to {@code target}. Returns the delivered target when successful.
     */
    Optional<String> send(String target, String message);
}
