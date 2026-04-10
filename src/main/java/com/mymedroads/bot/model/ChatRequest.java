package com.mymedroads.bot.model;

import lombok.Data;

@Data
public class ChatRequest {

    private String message;
    private String sessionId;
}
