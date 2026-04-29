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

    // Matches "<any greeting> Mira" at the start of a message, across languages
    private static final Pattern GREETING_PATTERN = Pattern.compile(
        "(?i)^\\s*(" +
        // English
        "hi|hello|hey|howdy|greetings|" +
        "good\\s+(?:morning|afternoon|evening|night|day)|" +
        // Hindi / Sanskrit-based (transliterated)
        "namaste|namaskar[am]?|namasthe|pranam|" +
        // South Indian (transliterated)
        "vanakkam|namaskara|namaskaram|shubhodaya|" +
        // Punjabi (transliterated)
        "sat\\s+sri\\s+akal|waheguru|" +
        // Bengali (transliterated)
        "nomoshkar|" +
        // Gujarati (transliterated)
        "kem\\s+cho|" +
        // Native scripts: Hindi, Tamil, Kannada, Telugu, Bengali, Punjabi, Malayalam
        "नमस्ते|नमस्कार|" +
        "வணக்கம்|" +
        "ನಮಸ್ಕಾರ|" +
        "నమస్కారం|వనక్కం|" +
        "নমস্কার|" +
        "ਸਤ\\s+ਸ੍ਰੀ\\s+ਅਕਾਲ|" +
        "നമസ്കാരം|" +
        // Spanish
        "hola|buenos?\\s+(?:d[íi]as?|tardes?|noches?)|" +
        // French
        "bonjour|salut|bonsoir|" +
        // German
        "hallo|guten\\s+(?:morgen|tag|abend)|" +
        // Arabic (transliterated + native script)
        "marhaba|salam|ahlan|as-?salamu\\s+alaykum|" +
        "مرحبا|سلام|أهلاً|أهلا|السلام\\s+عليكم|صباح\\s+الخير|مساء\\s+الخير|أهلاً\\s+وسهلاً|" +
        // Italian
        "ciao|" +
        // Portuguese
        "oi|ol[aá]|bom\\s+dia|boa\\s+(?:tarde|noite)|" +
        // Japanese (transliterated)
        "konnichiwa|ohayo[u]?|konbanwa|" +
        // Korean (transliterated)
        "annyeong(?:haseyo)?|" +
        // Russian (transliterated)
        "privet|zdravstvuyte|" +
        // Swahili
        "jambo|habari" +
        ")\\s+mira\\b.*",
        Pattern.DOTALL
    );

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

    private static final Pattern CONFIRM_YES_PATTERN =
            Pattern.compile("(?i)^\\s*(yes|yeah|yep|yup|sure|ok|okay|y|new|start\\s+new|new\\s+session|" +
                    "haan|ji\\s+haan|bilkul|ha|نعم|أجل|بله|oui|ja|sí|si|sim|da|はい|네|да)\\s*[.!]*\\s*$");

    public ChatResponse chat(ChatRequest request) {
        String sessionId = request.getSessionId();
        boolean sessionExisted = sessionId != null && sessionStore.sessionExists(sessionId);

        // If we asked the user whether to start a new session, handle their reply
        if (sessionExisted && sessionStore.isPendingNewSession(sessionId)) {
            sessionStore.clearPendingNewSession(sessionId);
            if (CONFIRM_YES_PATTERN.matcher(request.getMessage()).matches()) {
                String languageHint = sessionStore.getLanguageHint(sessionId);
                sessionStore.clearSession(sessionId);
                sessionId = sessionStore.createSession();
                log.debug("User confirmed new session: {}", sessionId);
                return buildDirectResponse(sessionId, generateIntroduction(languageHint.isEmpty() ? request.getMessage() : languageHint));
            } else {
                log.debug("User declined new session, continuing: {}", sessionId);
                return buildDirectResponse(sessionId, "No problem! Let's continue from where we left off. How can I help you?");
            }
        }

        // When greeted, ask before discarding an existing session; start fresh otherwise
        if (GREETING_PATTERN.matcher(request.getMessage()).matches()) {
            if (sessionExisted) {
                sessionStore.setLanguageHint(sessionId, request.getMessage());
                sessionStore.markPendingNewSession(sessionId);
                log.debug("Greeting detected with existing session, asking for confirmation: {}", sessionId);
                return buildDirectResponse(sessionId, generateWelcomeBackMessage(request.getMessage()));
            }
            sessionId = sessionStore.createSession();
            sessionStore.markNeedsIntroduction(sessionId);
            log.debug("Created new session on greeting: {}", sessionId);
        } else if (!sessionExisted) {
            sessionId = sessionStore.createSession();
            sessionStore.markNeedsIntroduction(sessionId);
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
        log.info("Rag Context: {}", ragContext);
        String effectiveSystemPrompt = ragContext.isEmpty()
                ? systemPrompt
                : systemPrompt + "\n\n" + ragContext;

        // On the first turn of a new session, prepend an introduction instruction
        if (sessionStore.needsIntroduction(sessionId)) {
            effectiveSystemPrompt = "IMPORTANT: Begin your response by warmly and excitedly introducing yourself " +
                    "as Mira, a caring medical travel assistant from myMedRoads, " +
                    "in the EXACT SAME LANGUAGE as the user's message. " +
                    "Then proceed to address the user's message.\n\n" + effectiveSystemPrompt;
            sessionStore.clearNeedsIntroduction(sessionId);
        }

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

    private String generateIntroduction(String languageHint) {
        String prompt = "You are Mira, a warm and caring medical travel assistant from myMedRoads. " +
                "Generate a warm, excited self-introduction in the EXACT SAME LANGUAGE as this text: \"" + languageHint + "\". " +
                "Introduce yourself as Mira, express genuine happiness to meet the user, " +
                "and let them know you are here to help with their medical travel needs. " +
                "Keep it to 2-3 sentences. Do not switch languages.";
        Message response = anthropicClient.messages().create(
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(256)
                        .addUserMessage(prompt)
                        .build());
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(tb -> tb.text())
                .reduce("", (a, b) -> a + b);
    }

    private String generateWelcomeBackMessage(String userGreeting) {
        String prompt = "You are Mira, a warm and caring medical travel assistant. " +
                "A returning user has just greeted you with: \"" + userGreeting + "\". " +
                "Respond with an excited, heartfelt welcome-back message in the EXACT SAME LANGUAGE as the greeting. " +
                "Express genuine happiness at seeing them again. " +
                "Then ask them whether they would like to continue from where they left off or start a fresh conversation. " +
                "Keep it brief — 2 to 3 sentences only. Do not switch languages.";
        Message response = anthropicClient.messages().create(
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(256)
                        .addUserMessage(prompt)
                        .build());
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(tb -> tb.text())
                .reduce("", (a, b) -> a + b);
    }

    private ChatResponse buildDirectResponse(String sessionId, String message) {
        return ChatResponse.builder()
                .sessionId(sessionId)
                .message(message)
                .intakeComplete(false)
                .build();
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
