package com.mymedroads.bot.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.mymedroads.bot.model.ChatMessage;
import com.mymedroads.bot.model.ChatRequest;
import com.mymedroads.bot.model.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeService {

    private final AnthropicClient anthropicClient;
    private final ConversationSessionStore sessionStore;

    @Value("${anthropic.model}")
    private String model;

    @Value("${anthropic.max-tokens}")
    private long maxTokens;

    @Value("${anthropic.system-prompt}")
    private String systemPrompt;

    public ChatResponse chat(ChatRequest request) {
        // Resolve or create session
        String sessionId = request.getSessionId();
        if (sessionId == null || !sessionStore.sessionExists(sessionId)) {
            sessionId = sessionStore.createSession();
            log.debug("Created new session: {}", sessionId);
        }

        // Add user message to history
        ChatMessage userMessage = ChatMessage.builder()
                .role("user")
                .content(request.getMessage())
                .build();
        sessionStore.addMessage(sessionId, userMessage);

        // Build message params from conversation history
        List<ChatMessage> history = sessionStore.getHistory(sessionId);
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder().text(systemPrompt).build()
                ));

        for (ChatMessage msg : history) {
            if ("user".equals(msg.getRole())) {
                paramsBuilder.addUserMessage(msg.getContent());
            } else {
                paramsBuilder.addAssistantMessage(msg.getContent());
            }
        }

        log.debug("Sending {} messages to Claude (session: {})", history.size(), sessionId);

        // Call Claude API
        Message response = anthropicClient.messages().create(paramsBuilder.build());

        // Extract text response
        String assistantText = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .reduce("", (a, b) -> a + b);

        // Store assistant reply in history
        ChatMessage assistantMessage = ChatMessage.builder()
                .role("assistant")
                .content(assistantText)
                .build();
        sessionStore.addMessage(sessionId, assistantMessage);

        log.debug("Received response ({} chars) for session: {}", assistantText.length(), sessionId);

        return ChatResponse.builder()
                .sessionId(sessionId)
                .message(assistantText)
                .model(response.model().toString())
                .inputTokens(response.usage().inputTokens())
                .outputTokens(response.usage().outputTokens())
                .build();
    }
}
