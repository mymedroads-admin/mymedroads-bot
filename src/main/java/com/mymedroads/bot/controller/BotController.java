package com.mymedroads.bot.controller;

import com.mymedroads.bot.model.ChatRequest;
import com.mymedroads.bot.model.ChatResponse;
import com.mymedroads.bot.service.ClaudeService;
import com.mymedroads.bot.service.ConversationSessionStore;
import com.mymedroads.bot.service.KnowledgeIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class BotController {

    private final ClaudeService claudeService;
    private final ConversationSessionStore sessionStore;
    private final KnowledgeIngestionService ingestionService;

    private static final long INTERIM_THRESHOLD_MS = 10_000;

    private static final java.util.regex.Pattern CLEAR_INTENT_PATTERN = java.util.regex.Pattern.compile(
            "(?i)\\b(clear|delete|reset|forget|erase|wipe)\\b.*\\b(conversation|history|chat|messages?|session)\\b"
            + "|\\b(start\\s+(over|fresh|new|afresh)|forget\\s+everything|new\\s+conversation)\\b"
    );

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest request) {
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message cannot be blank"));
        }
        log.info("Received chat request for session: {}", request.getSessionId());

        boolean clearIntent = CLEAR_INTENT_PATTERN.matcher(request.getMessage()).find();
        if (clearIntent) {
            log.info("Clear-conversation intent detected for session: {}", request.getSessionId());
            sessionStore.clearSession(request.getSessionId());
        }

        long start = System.currentTimeMillis();
        ChatResponse response = claudeService.chat(request);
        response.setInterimShown(System.currentTimeMillis() - start > INTERIM_THRESHOLD_MS);

        if (clearIntent) {
            response.setMessage("Your conversation history has been cleared.\n\n" + response.getMessage());
        }

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

    /**
     * Delete all vector store chunks for a given source (filename or URL).
     * Body: { "source": "document.txt" }  or  { "source": "https://example.com/page" }
     */
    @DeleteMapping("/admin/ingest/remove")
    public ResponseEntity<Map<String, Object>> deleteBySource(@RequestBody Map<String, String> body) throws Exception {
        String source = body.get("source");
        if (source == null || source.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "source is required"));
        }
        int deleted = ingestionService.deleteBySource(source);
        if (deleted == 0) {
            return ResponseEntity.ok(Map.of("status", "not_found", "source", source, "chunksDeleted", 0));
        }
        return ResponseEntity.ok(Map.of("status", "deleted", "source", source, "chunksDeleted", deleted));
    }

    /**
     * Ingest an uploaded document along with a brief description.
     * Multipart form fields:
     *   file        — the document file (plain text)
     *   description — a brief summary of what the document contains
     */
    @PostMapping(value = "/admin/ingest/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> ingestUpload(
            @RequestPart("file") MultipartFile file,
            @RequestPart("description") String description) throws Exception {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file must not be empty"));
        }
        if (description == null || description.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "description is required"));
        }

        log.info("Starting upload ingestion: {} — {}", file.getOriginalFilename(), description);
        int chunks = ingestionService.ingestUploadedDocument(file, description);
        return ResponseEntity.ok(Map.of(
                "status", "complete",
                "filename", file.getOriginalFilename(),
                "description", description,
                "chunksIngested", chunks));
    }
}