package com.mymedroads.bot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mymedroads.bot.model.ChatMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for conversation sessions.
 * Each session holds the full message history for multi-turn conversations.
 * Sessions are persisted to disk every minute and restored on startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSessionStore {

    private static final String SESSIONS_FILE = "/var/lib/tomcat10/sessions/bot_claude_sessions.json";

    private final ObjectMapper objectMapper;

    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadSessionsFromDisk() {
        File file = new File(SESSIONS_FILE);
        if (!file.exists()) {
            log.info("No session file found at {}; starting with empty session store", SESSIONS_FILE);
            return;
        }
        try {
            Map<String, List<ChatMessage>> loaded = objectMapper.readValue(
                    file, new TypeReference<Map<String, List<ChatMessage>>>() {});
            sessions.putAll(loaded);
            log.info("Loaded {} session(s) from {}", loaded.size(), SESSIONS_FILE);
        } catch (Exception e) {
            log.error("Failed to load sessions from {}: {}", SESSIONS_FILE, e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void saveSessionsToDisk() {
        File file = new File(SESSIONS_FILE);
        try {
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, sessions);
            log.debug("Saved {} session(s) to {}", sessions.size(), SESSIONS_FILE);
        } catch (Exception e) {
            log.error("Failed to save sessions to {}: {}", SESSIONS_FILE, e.getMessage(), e);
        }
    }

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        sessions.put(sessionId, new ArrayList<>());
        return sessionId;
    }

    public List<ChatMessage> getHistory(String sessionId) {
        return sessions.getOrDefault(sessionId, new ArrayList<>());
    }

    public void addMessage(String sessionId, ChatMessage message) {
        sessions.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
    }

    public boolean sessionExists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public void clearSession(String sessionId) {
        sessions.remove(sessionId);
    }
}
