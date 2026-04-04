package com.mymedroads.bot.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {

    /**
     * The user's message.
     */
    @NotBlank(message = "Message cannot be blank")
    private String message;

    /**
     * Optional session ID for multi-turn conversations.
     * If null, a new session is started.
     */
    private String sessionId;
}
