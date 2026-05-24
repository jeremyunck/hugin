package com.example.integration.controller;

import com.example.agent.AgentService;
import com.example.agent.model.AgentRequest;
import com.example.agent.model.AgentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoint for the AI agent.
 *
 * <p>Example request:
 * <pre>{@code
 * POST /api/agent/chat
 * { "prompt": "What time is it in Tokyo?", "model": "llama3.2" }
 * }</pre>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody AgentRequest request) {
        log.debug("POST /api/agent/chat model={}, prompt=\"{}\"", request.model(), request.prompt());
        AgentResponse response = agentService.chat(request);
        log.debug("POST /api/agent/chat response: content=\"{}\", messages={}",
                response.content(), response.messages().size());
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleError(Exception ex) {
        log.debug("POST /api/agent/chat error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
    }
}
