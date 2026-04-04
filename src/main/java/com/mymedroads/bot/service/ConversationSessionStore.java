package com.mymedroads.bot.service;

import com.mymedroads.bot.model.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for conversation sessions.
 * Each session holds the full message history for multi-turn conversations.
 */
@Component
public class ConversationSessionStore {

    private final Map<String, List<ChatMessage>> sessions = new ConcurrentHashMap<>();

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
