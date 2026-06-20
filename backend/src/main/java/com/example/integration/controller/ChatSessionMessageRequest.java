package com.example.integration.controller;

import com.example.agent.model.ChatAttachment;

import java.util.List;

public record ChatSessionMessageRequest(
        String content,
        String mode,
        String title,
        List<ChatAttachment> attachments,
        String model,
        String reasoningEffort,
        String sandboxId
) {}
