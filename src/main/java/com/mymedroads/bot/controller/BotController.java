package com.mymedroads.bot.controller;

import com.mymedroads.bot.model.ChatRequest;
import com.mymedroads.bot.model.ChatResponse;
import com.mymedroads.bot.service.ClaudeService;
import com.mymedroads.bot.service.ConversationSessionStore;
import com.mymedroads.bot.service.KnowledgeIngestionService;
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
    private final KnowledgeIngestionService ingestionService;

    /**
     * Send a message to the bot and get a response.
     * Include a sessionId in the request body to continue an existing conversation.
     * Omit sessionId (or set to null) to start a new conversation.
     */
    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message cannot be blank"));
        }
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

    /**
     * Ingest all documents from classpath:knowledge/ (PDFs and TXT files).
     * Call this once after adding or updating documents.
     */
    @PostMapping("/admin/ingest/documents")
    public ResponseEntity<Map<String, Object>> ingestDocuments() throws Exception {
        log.info("Starting document ingestion...");
        int chunks = ingestionService.ingestDocuments();
        return ResponseEntity.ok(Map.of("status", "complete", "chunksIngested", chunks));
    }

    /**
     * Ingest content from a URL.
     * Body: { "url": "https://uat.mymedroads.com/hospitals" }
     */
    @PostMapping("/admin/ingest/url")
    public ResponseEntity<Map<String, Object>> ingestUrl(@RequestBody Map<String, String> body) throws Exception {
        String url = body.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }
        log.info("Starting URL ingestion: {}", url);
        int chunks = ingestionService.ingestUrl(url);
        return ResponseEntity.ok(Map.of("status", "complete", "url", url, "chunksIngested", chunks));
    }
}
