package com.example.integration.controller;

/**
 * Body of {@code POST /api/chat/sessions/{sessionId}/approvals/{approvalId}}: the user's decision on a
 * pending tool action. {@code decision} is {@code "approve"} or {@code "decline"}.
 */
public record ChatApprovalDecisionRequest(String decision) {}
