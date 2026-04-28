package com.mymedroads.bot.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mymedroads.bot.model.ChatMessage;
import com.mymedroads.bot.model.ChatRequest;
import com.mymedroads.bot.model.ChatResponse;
import com.mymedroads.bot.model.PatientProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeService {

    // Matches the machine-readable marker Claude emits once intake is confirmed:
    // [INTAKE_COMPLETE:{"name":"...","age":"...","gender":"...","mobile":"...","email":"...","destination":"...","medicalIssue":"..."}]
    private static final Pattern INTAKE_MARKER =
            Pattern.compile("\\[INTAKE_COMPLETE:(\\{.*?\\})\\]", Pattern.DOTALL);

    // Matches the marker Claude emits when user asks about their case status:
    // [CASE_STATUS_REQUEST:<urn>]
    private static final Pattern CASE_STATUS_MARKER =
            Pattern.compile("\\[CASE_STATUS_REQUEST:([^\\]]+)\\]");

    private final AnthropicClient anthropicClient;
    private final ConversationSessionStore sessionStore;
    private final RagService ragService;
    private final PatientLeadApiService patientLeadApiService;
    private final ObjectMapper objectMapper;

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

        // Augment system prompt with RAG context relevant to this query
        String ragContext = ragService.retrieveContext(request.getMessage());
        String effectiveSystemPrompt = ragContext.isEmpty()
                ? systemPrompt
                : systemPrompt + "\n\n" + ragContext;

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder().text(effectiveSystemPrompt).build()
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

        // Detect the intake-complete marker emitted by Claude after confirmation
        boolean intakeComplete = false;
        String visibleText = assistantText;
        Matcher matcher = INTAKE_MARKER.matcher(assistantText);
        if (matcher.find()) {
            visibleText = assistantText.replace(matcher.group(0), "").strip();
            if (!sessionStore.isIntakeCompleted(sessionId)) {
                intakeComplete = true;
                sessionStore.markIntakeCompleted(sessionId);
                Optional<String> refNumber = submitPatientLead(matcher.group(1), sessionId);
                if (refNumber.isPresent()) {
                    visibleText = visibleText + "\n\nYour unique reference number is **" + refNumber.get()
                            + "**. Please save this for future correspondence.";
                }
            } else {
                log.debug("Ignoring duplicate INTAKE_COMPLETE marker for session: {}", sessionId);
            }
        }

        // Detect the case-status marker emitted by Claude when user asks about their case
        Matcher statusMatcher = CASE_STATUS_MARKER.matcher(visibleText);
        if (statusMatcher.find()) {
            String urn = statusMatcher.group(1).strip();
            visibleText = visibleText.replace(statusMatcher.group(0), "").strip();
            Map<String, Object> statusResponse = patientLeadApiService.fetchCaseStatus(urn);
            visibleText = visibleText + "\n\n" + formatCaseStatus(urn, statusResponse);
        }

        // Store assistant reply in history (without the marker)
        ChatMessage assistantMessage = ChatMessage.builder()
                .role("assistant")
                .content(visibleText)
                .build();
        sessionStore.addMessage(sessionId, assistantMessage);

        log.debug("Received response ({} chars) for session: {}", visibleText.length(), sessionId);

        return ChatResponse.builder()
                .sessionId(sessionId)
                .message(visibleText)
                .intakeComplete(intakeComplete)
                .model(response.model().toString())
                .inputTokens(response.usage().inputTokens())
                .outputTokens(response.usage().outputTokens())
                .build();
    }

    private String formatCaseStatus(String urn, Map<String, Object> statusResponse) {
        if (statusResponse.containsKey("error")) {
            return "I'm sorry, I was unable to fetch the status of your case at this time. "
                    + "Please contact myMedRoads support at contact@mymedroads.com or call/WhatsApp +91-9844837371.";
        }
        String dataDescription = statusResponse.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().toString().isBlank())
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("no details available");

        String prompt = "The user asked about their case status. Their URN is " + urn
                + ". Here is the case data retrieved from the system: " + dataDescription
                + ". As Mira, present this information to the user in a warm, clear, and easy-to-understand way. "
                + "Do not include any marker or JSON. Keep it concise.";

        Message formatted = anthropicClient.messages().create(
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(256)
                        .addUserMessage(prompt)
                        .build());

        return formatted.content().stream()
                .flatMap(block -> block.text().stream())
                .map(tb -> tb.text())
                .reduce("", (a, b) -> a + b);
    }

    private Optional<String> submitPatientLead(String profileJson, String sessionId) {
        try {
            PatientProfile profile = objectMapper.readValue(profileJson, PatientProfile.class);
            return patientLeadApiService.submitLead(profile, sessionId);
        } catch (Exception e) {
            log.error("Failed to parse patient profile JSON for session {}: {}", sessionId, e.getMessage(), e);
            return Optional.empty();
        }
    }
}
