package com.mymedroads.bot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String sessionId;
    private String message;
    @JsonIgnore
    private boolean intakeComplete;
    private boolean interimShown;
    private String model;
    private long inputTokens;
    private long outputTokens;
}
