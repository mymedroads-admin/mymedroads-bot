package com.mymedroads.bot.controller;

import com.mymedroads.bot.model.ChatRequest;
import com.mymedroads.bot.model.ChatResponse;
import com.mymedroads.bot.service.ClaudeService;
import com.mymedroads.bot.service.ConversationSessionStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class BotController {

    private final ClaudeService claudeService;
    private final ConversationSessionStore sessionStore;

    /**
     * Send a message to the bot and get a response.
     * Include a sessionId in the request body to continue an existing conversation.
     * Omit sessionId (or set to null) to start a new conversation.
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        log.info("Received chat request for session: {}", request.getSessionId());
        ChatResponse response = claudeService.chat(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Start a new conversation session explicitly.
     */
    @PostMapping("/session/new")
    public ResponseEntity<Map<String, String>> newSession() {
        String sessionId = sessionStore.createSession();
        log.info("Created new session: {}", sessionId);
        return ResponseEntity.ok(Map.of("sessionId", sessionId));
    }

    /**
     * Clear a conversation session and its history.
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId) {
        sessionStore.clearSession(sessionId);
        log.info("Cleared session: {}", sessionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "mymedroads-bot"));
    }
}
