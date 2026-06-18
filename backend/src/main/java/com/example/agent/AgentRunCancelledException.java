package com.example.agent;

public class AgentRunCancelledException extends RuntimeException {
    public AgentRunCancelledException(String message) {
        super(message);
    }

    public AgentRunCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
