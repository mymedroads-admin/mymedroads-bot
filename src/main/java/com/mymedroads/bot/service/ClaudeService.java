package com.mymedroads.bot.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mymedroads.bot.model.ChatMessage;
import com.mymedroads.bot.model.ChatRequest;
import com.mymedroads.bot.model.ChatResponse;
import com.mymedroads.bot.model.PatientProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
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

    // Matches the marker Claude emits when the user updates any intake field post-completion:
    // [INTAKE_UPDATE:{"urn":"...","name":"...","age":"...","gender":"...","mobile":"...","email":"...","destination":"...","medicalIssue":"..."}]
    private static final Pattern INTAKE_UPDATE_MARKER =
            Pattern.compile("\\[INTAKE_UPDATE:(\\{.*?\\})\\]", Pattern.DOTALL);

    // Matches "<any greeting> Mira" at the start of a message, across languages
    private static final Pattern GREETING_PATTERN = Pattern.compile(
        "(?i)^\\s*(" +
        // English
        "hi|hello|hey|howdy|greetings|good\\s+(?:morning|afternoon|evening|night|day)|" +
        // Hindi: namaste (\u0928\u092E\u0938\u094D\u0924\u0947), namaskar (\u0928\u092E\u0938\u094D\u0915\u093E\u0930)
        "namaste|namaskar[am]?|namasthe|pranam|\u0928\u092E\u0938\u094D\u0924\u0947|\u0928\u092E\u0938\u094D\u0915\u093E\u0930|" +
        // Bengali: nomoshkar (\u09A8\u09AE\u09B8\u09CD\u0995\u09BE\u09B0)
        "nomoshkar|\u09A8\u09AE\u09B8\u09CD\u0995\u09BE\u09B0|" +
        // Swahili
        "jambo|habari|" +
        // Arabic: marhaba, salam, ahlan, as-salamu alaykum, sabah/masa al-khayr
        "marhaba|salam|ahlan|as-?salamu\\s+alaykum|" +
        "\u0645\u0631\u062D\u0628\u0627|\u0633\u0644\u0627\u0645|\u0623\u0647\u0644\u0627\u064B|\u0623\u0647\u0644\u0627|\u0627\u0644\u0633\u0644\u0627\u0645\\s+\u0639\u0644\u064A\u0643\u0645|" +
        "\u0635\u0628\u0627\u062D\\s+\u0627\u0644\u062E\u064A\u0631|\u0645\u0633\u0627\u0621\\s+\u0627\u0644\u062E\u064A\u0631|" +
        // French
        "bonjour|salut|bonsoir|" +
        // Spanish: hola, buenos dias/tardes/noches (\u00ED=accented i)
        "hola|buenos?\\s+(?:d[i\u00ED]as?|tardes?|noches?)|" +
        // German: hallo, guten morgen/tag/abend, servus, moin
        "hallo|guten\\s+(?:morgen|tag|abend)|servus|moin|" +
        // Russian: privet (\u043F\u0440\u0438\u0432\u0435\u0442), zdravstvuyte (\u0437\u0434\u0440\u0430\u0432\u0441\u0442\u0432\u0443\u0439\u0442\u0435), dobry den/utro/vecher
        "privet|zdravstvuyte|\u043F\u0440\u0438\u0432\u0435\u0442|\u0437\u0434\u0440\u0430\u0432\u0441\u0442\u0432\u0443\u0439\u0442\u0435|\u0434\u043E\u0431\u0440\u044B\u0439\\s+\u0434\u0435\u043D\u044C|\u0434\u043E\u0431\u0440\u043E\u0435\\s+\u0443\u0442\u0440\u043E|\u0434\u043E\u0431\u0440\u044B\u0439\\s+\u0432\u0435\u0447\u0435\u0440|" +
        // Amharic: selam (\u1230\u120B\u121D), tenaystilign (\u1324\u1293\u12ED\u1235\u1325\u120D\u129D), endemin (\u12A5\u1295\u12F0\u121D\u1295...)
        "selam|tenaystilign|\u1230\u120B\u121D|\u1324\u1293\u12ED\u1235\u1325\u120D\u129D|\u12A5\u1295\u12F0\u121D\u1295|" +
        // Chinese Mandarin: ni hao, nin hao, zao shang hao, wan shang hao, xia wu hao
        "\u4F60\u597D|\u60A8\u597D|\u65E9\u4E0A\u597D|\u665A\u4E0A\u597D|\u4E0B\u5348\u597D" +
        ")\\s+mira\\b.*",
        Pattern.DOTALL
    );

    // Matches the marker Claude emits when user asks about their case status:
    // [CASE_STATUS_REQUEST:<urn>]
    // private static final Pattern CASE_STATUS_MARKER =
    //         Pattern.compile("\\[CASE_STATUS_REQUEST:([^\\]]+)\\]");

    // Matches the marker Claude emits when user requests a full case summary, providing URN and last 4 mobile digits:
    // [CASE_SUMMARY_REQUEST:<urn>:<last4>]
    private static final Pattern CASE_SUMMARY_MARKER =
            Pattern.compile("\\[CASE_SUMMARY_REQUEST:([^:\\]]+):([^\\]]+)\\]");

    // Matches the marker Claude emits after the user selects a language:
    // [LANGUAGE_SELECTED:<language_name>]
    private static final Pattern LANGUAGE_SELECTED_MARKER =
            Pattern.compile("\\[LANGUAGE_SELECTED:([^\\]]+)\\]");

    private static final String SUPPORTED_LANGUAGES_LIST =
            "1. English\n2. Hindi\n3. Bengali\n4. Swahili\n5. Arabic\n" +
            "6. French\n7. Spanish\n8. Chinese Mandarin\n9. German\n10. Russian\n11. Amharic";

    private static final String LANGUAGE_SELECTION_PROMPT =
            "I support the following languages. Please reply with the number or name of your preferred language " +
            "and I will continue our conversation in that language. The default is English.\n\n" +
            SUPPORTED_LANGUAGES_LIST;

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

    @Value("${mymedroads-api-suite.url}")
    private String apiUrl;

    private static final Pattern CONFIRM_YES_PATTERN =
            Pattern.compile("(?i)^\\s*(yes|yeah|yep|yup|sure|ok|okay|y|new|start\\s+new|new\\s+session|" +
                    "haan|ji\\s+haan|bilkul|ha|نعم|أجل|بله|oui|ja|sí|si|sim|da|はい|네|да)\\s*[.!]*\\s*$");

    public ChatResponse chat(ChatRequest request) {
        // Resolve or generate a persistent client identifier
        String clientId = (request.getClientId() != null && !request.getClientId().isBlank())
                ? request.getClientId()
                : UUID.randomUUID().toString();

        String sessionId = request.getSessionId();
        boolean sessionExisted = sessionId != null && sessionStore.sessionExists(sessionId);

        // For existing sessions, link the clientId and restore language if lost after a restart
        if (sessionExisted) {
            sessionStore.linkSessionToClient(sessionId, clientId);
            if (sessionStore.getSelectedLanguage(sessionId).isEmpty()) {
                String savedLang = sessionStore.getClientLanguagePreference(clientId);
                if (!savedLang.isEmpty()) {
                    sessionStore.setSelectedLanguage(sessionId, savedLang);
                }
            }
        }

        // If we asked the user whether to start a new session, handle their reply
        if (sessionExisted && sessionStore.isPendingNewSession(sessionId)) {
            sessionStore.clearPendingNewSession(sessionId);
            if (CONFIRM_YES_PATTERN.matcher(request.getMessage()).matches()) {
                String languageHint = sessionStore.getLanguageHint(sessionId);
                sessionStore.clearSession(sessionId);
                sessionId = sessionStore.createSession();
                sessionStore.linkSessionToClient(sessionId, clientId);
                String savedLanguage = sessionStore.getClientLanguagePreference(clientId);
                boolean langKnown = !savedLanguage.isEmpty();
                if (langKnown) {
                    sessionStore.setSelectedLanguage(sessionId, savedLanguage);
                } else {
                    sessionStore.markPendingLanguageSelection(sessionId);
                }
                log.debug("User confirmed new session: {}", sessionId);
                String intro = generateIntroduction(languageHint.isEmpty() ? request.getMessage() : languageHint);
                String introMessage = langKnown ? intro : intro + "\n\n" + LANGUAGE_SELECTION_PROMPT;
                return ChatResponse.builder()
                        .clientId(clientId)
                        .sessionId(sessionId)
                        .message(introMessage)
                        .intakeComplete(false)
                        .build();
            } else {
                log.debug("User declined new session, continuing: {}", sessionId);
                return buildDirectResponse(clientId, sessionId, "No problem! Let's continue from where we left off. How can I help you?");
            }
        }

        // When greeted, ask before discarding an existing session; start fresh otherwise
        if (GREETING_PATTERN.matcher(request.getMessage()).matches()) {
            if (sessionExisted) {
                sessionStore.setLanguageHint(sessionId, request.getMessage());
                sessionStore.markPendingNewSession(sessionId);
                log.debug("Greeting detected with existing session, asking for confirmation: {}", sessionId);
                return buildDirectResponse(clientId, sessionId, generateWelcomeBackMessage(request.getMessage()));
            }
            sessionId = sessionStore.createSession();
            sessionStore.markNeedsIntroduction(sessionId);
            sessionStore.linkSessionToClient(sessionId, clientId);
            String savedLang = sessionStore.getClientLanguagePreference(clientId);
            if (!savedLang.isEmpty()) {
                sessionStore.setSelectedLanguage(sessionId, savedLang);
            }
            log.debug("Created new session on greeting: {}", sessionId);
        } else if (!sessionExisted) {
            sessionId = sessionStore.createSession();
            sessionStore.markNeedsIntroduction(sessionId);
            sessionStore.linkSessionToClient(sessionId, clientId);
            String savedLang = sessionStore.getClientLanguagePreference(clientId);
            if (!savedLang.isEmpty()) {
                sessionStore.setSelectedLanguage(sessionId, savedLang);
            }
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

        // If the user has already chosen a language, lock replies to that language
        String chosenLanguage = sessionStore.getSelectedLanguage(sessionId);
        if (!chosenLanguage.isEmpty()) {
            effectiveSystemPrompt = "IMPORTANT: The user has selected " + chosenLanguage +
                    " as their preferred language for this session. Respond exclusively in " +
                    chosenLanguage + " for every part of your reply.\n\n" + effectiveSystemPrompt;
        }

        boolean languageAlreadyKnown = !sessionStore.getSelectedLanguage(sessionId).isEmpty();

        boolean wasIntroTurn = false;
        if (sessionStore.needsIntroduction(sessionId)) {
            // First turn: introduce Mira. Skip language prompt if preference is already known.
            String introLanguage = languageAlreadyKnown ? sessionStore.getSelectedLanguage(sessionId) : "English";
            effectiveSystemPrompt = "IMPORTANT: Begin your response by warmly introducing yourself " +
                    "as Mira, a caring medical travel assistant from myMedRoads (https://uat.mymedroads.com). " +
                    "Mention that you are an AI assistant and may occasionally make mistakes. " +
                    "Do NOT mention languages, present a language list, or ask about language preferences in this message. " +
                    "Do NOT start the patient intake yet. Respond in " + introLanguage + " only.\n\n" + effectiveSystemPrompt;
            sessionStore.clearNeedsIntroduction(sessionId);
            wasIntroTurn = true;
        } else if (sessionStore.isPendingLanguageSelection(sessionId)) {
            // Second turn: user is responding with their language choice
            effectiveSystemPrompt = "IMPORTANT: The user is choosing their preferred language from the list you presented. " +
                    "Identify which language they selected (by number or name). " +
                    "Warmly confirm their choice and respond entirely in that language. " +
                    "Do NOT start the patient intake yet. " +
                    "At the very end of your response, append this marker on its own line " +
                    "(no spaces around the colon, no line breaks inside it):\n" +
                    "[LANGUAGE_SELECTED:<language_name>]\n" +
                    "where <language_name> is exactly one of: English, Hindi, Bengali, Swahili, Arabic, " +
                    "French, Spanish, Chinese Mandarin, German, Russian, Amharic.\n\n" + effectiveSystemPrompt;
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
                //Send a confirmation message to the user lead API
                String baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
                PatientProfile profile;
                try {
                    profile = new ObjectMapper().readValue(matcher.group(1), PatientProfile.class);
                    Map<String, String> payload = new LinkedHashMap<>();
                    payload.put("recipientEmail", profile.getEmail());
                    payload.put("urn", refNumber.get());
                    ZonedDateTime utcNow = ZonedDateTime.now(ZoneOffset.UTC);
                    int day = utcNow.getDayOfMonth();
                    String monthString= utcNow.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
                    String ordinal = (day % 10 == 1 && day != 11) ? "st"
                                   : (day % 10 == 2 && day != 12) ? "nd"
                                   : (day % 10 == 3 && day != 13) ? "rd" : "th";
                    String registrationDate = utcNow.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                                           + ", " + monthString + " " + day + ordinal + " " + utcNow.getYear();
                    payload.put("registration_date", registrationDate);

                    payload.put("channel", "Mira");
                    payload.put("name", profile.getName());
                    payload.put("gender", profile.getGender());
                    payload.put("age", String.valueOf(profile.getAge()));
                    payload.put("destination", profile.getDestination());
                    payload.put("medical_issue", profile.getMedicalIssue());

                    RestClient restClient = RestClient.builder().build();
                    ResponseEntity<Void> submitResponse = restClient.post()
                        .uri(baseUrl + "/sendemail/new_registration_confirmation_email")
                        .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                        .body(payload)
                        .retrieve()
                        .toBodilessEntity();
                    log.info("Confirmation email sent for session: {} with response: {}", sessionId, submitResponse.getStatusCode());

                    if (!submitResponse.getStatusCode().is2xxSuccessful()) {
                        visibleText = visibleText + "\n\n"
                                + generateEmailFailureMessage(assistantText, profile.getEmail());
                    }

                } catch (JsonProcessingException e) {
                    log.info("Failed to send confirmation email for session: {}", sessionId);

                }
            } else {
                log.debug("Ignoring duplicate INTAKE_COMPLETE marker for session: {}", sessionId);
            }
        }

        // Detect the language-selection marker emitted by Claude after user picks a language
        Matcher langMatcher = LANGUAGE_SELECTED_MARKER.matcher(visibleText);
        if (langMatcher.find()) {
            String language = langMatcher.group(1).strip();
            visibleText = visibleText.replace(langMatcher.group(0), "").strip();
            sessionStore.setSelectedLanguage(sessionId, language);
            sessionStore.clearPendingLanguageSelection(sessionId);
            sessionStore.setClientLanguagePreference(clientId, language);
            log.debug("Language selected: {} for session: {} (client: {})", language, sessionId, clientId);
        }

        // Detect the intake-update marker emitted by Claude when the user changes a field post-intake
        Matcher updateMatcher = INTAKE_UPDATE_MARKER.matcher(visibleText);
        if (updateMatcher.find()) {
            visibleText = visibleText.replace(updateMatcher.group(0), "").strip();
            try {
                Map<String, String> updateData = objectMapper.readValue(updateMatcher.group(1), new TypeReference<Map<String, String>>() {});
                String updateUrn = updateData.get("urn");
                PatientProfile updatedProfile = PatientProfile.builder()
                        .name(updateData.get("name"))
                        .age(updateData.get("age"))
                        .gender(updateData.get("gender"))
                        .mobile(updateData.get("mobile"))
                        .email(updateData.get("email"))
                        .destination(updateData.get("destination"))
                        .medicalIssue(updateData.get("medicalIssue"))
                        .accommodationPreference(updateData.get("accommodationPreference"))
                        .budgetRange(updateData.get("budgetRange"))
                        .preferredHospital(updateData.get("preferredHospital"))
                        .preferredDoctor(updateData.get("preferredDoctor"))
                        .build();
                patientLeadApiService.updateLead(updatedProfile, updateUrn, sessionId);
            } catch (Exception e) {
                log.error("Failed to process INTAKE_UPDATE marker for session {}: {}", sessionId, e.getMessage(), e);
            }
        }

        // Detect the case-status marker emitted by Claude when user asks about their case
        // Matcher statusMatcher = CASE_STATUS_MARKER.matcher(visibleText);
        // if (statusMatcher.find()) {
        //     String urn = statusMatcher.group(1).strip();
        //     visibleText = visibleText.replace(statusMatcher.group(0), "").strip();
        //     Map<String, Object> statusResponse = patientLeadApiService.fetchCaseStatus(urn);
        //     visibleText = visibleText + "\n\n" + formatCaseStatus(urn, statusResponse);
        // }

        // Detect the case-summary marker emitted by Claude after user provides URN and last 4 mobile digits
        Matcher summaryMatcher = CASE_SUMMARY_MARKER.matcher(visibleText);
        if (summaryMatcher.find()) {
            String urn = summaryMatcher.group(1).strip();
            String mobile4 = summaryMatcher.group(2).strip();
            visibleText = visibleText.replace(summaryMatcher.group(0), "").strip();
            Map<String, Object> summaryResponse = patientLeadApiService.fetchCaseSummary(urn, mobile4);
            visibleText = visibleText + "\n\n" + formatCaseSummary(urn, summaryResponse);
        }

        // Append language selection to the intro message so it is always in the main message field
        if (wasIntroTurn && !languageAlreadyKnown) {
            visibleText = visibleText + "\n\n" + LANGUAGE_SELECTION_PROMPT;
            sessionStore.markPendingLanguageSelection(sessionId);
        }

        // Store assistant reply in history
        sessionStore.addMessage(sessionId, ChatMessage.builder()
                .role("assistant")
                .content(visibleText)
                .build());

        log.debug("Received response ({} chars) for session: {}", visibleText.length(), sessionId);

        return ChatResponse.builder()
                .clientId(clientId)
                .sessionId(sessionId)
                .message(visibleText)
                .intakeComplete(intakeComplete)
                .model(response.model().toString())
                .inputTokens(response.usage().inputTokens())
                .outputTokens(response.usage().outputTokens())
                .build();
    }

    // private String formatCaseStatus(String urn, Map<String, Object> statusResponse) {
    //     if (statusResponse.containsKey("error")) {
    //         return "I'm sorry, I was unable to fetch the status of your case at this time. "
    //                 + "Please contact myMedRoads support at contact@mymedroads.com or call/WhatsApp +91-9844837371.";
    //     }
    //     String dataDescription = statusResponse.entrySet().stream()
    //             .filter(e -> e.getValue() != null && !e.getValue().toString().isBlank())
    //             .map(e -> e.getKey() + ": " + e.getValue())
    //             .reduce((a, b) -> a + ", " + b)
    //             .orElse("no details available");

    //     String prompt = "The user asked about their case status. Their URN is " + urn
    //             + ". Here is the case data retrieved from the system: " + dataDescription
    //             + ". As Mira, present this information to the user in a warm, clear, and easy-to-understand way. "
    //             + "Do not include any marker or JSON. Keep it concise.";

    //     Message formatted = anthropicClient.messages().create(
    //             MessageCreateParams.builder()
    //                     .model(model)
    //                     .maxTokens(256)
    //                     .addUserMessage(prompt)
    //                     .build());

    //     return formatted.content().stream()
    //             .flatMap(block -> block.text().stream())
    //             .map(tb -> tb.text())
    //             .reduce("", (a, b) -> a + b);
    // }

    private String formatCaseSummary(String urn, Map<String, Object> summaryResponse) {
        if (summaryResponse.containsKey("error")) {
            return "I'm sorry, I was unable to fetch the summary of your case at this time. "
                    + "Please contact myMedRoads support at contact@mymedroads.com or call/WhatsApp +91-9844837371.";
        }
        String dataDescription = summaryResponse.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().toString().isBlank())
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("no details available");

        String prompt = "The user has requested a full summary of their case. Their URN is " + urn
                + ". Here is the complete case data retrieved from the system: " + dataDescription
                + ". As Mira, present this as a comprehensive, well-structured case summary in a warm and easy-to-understand way. "
                + "Cover all available details — patient information, medical issue, destination, preferences, and current status. "
                + "Do not include any marker or JSON. Keep it well-organised and concise.";

        Message formatted = anthropicClient.messages().create(
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(512)
                        .addUserMessage(prompt)
                        .build());

        return formatted.content().stream()
                .flatMap(block -> block.text().stream())
                .map(tb -> tb.text())
                .reduce("", (a, b) -> a + b);
    }

    private String generateIntroduction(String languageHint) {
        String prompt = "You are Mira, a warm and caring medical travel assistant from myMedRoads (https://uat.mymedroads.com). " +
                "Generate a warm self-introduction in English. " +
                "Introduce yourself as Mira, mention you are an AI assistant and may occasionally make mistakes, " +
                "and express happiness to meet the user. " +
                "Do NOT mention languages, present a language list, or ask about language preferences. " +
                "Keep it concise. Respond in English only.";
        Message response = anthropicClient.messages().create(
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(384)
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

    private String generateEmailFailureMessage(String languageHint, String email) {
        String prompt = "You are Mira, a warm and caring medical travel assistant from myMedRoads. "
                + "Inform the patient in the EXACT SAME LANGUAGE as the following text that the confirmation email "
                + "could not be delivered to **" + email + "**, and ask them to verify their email address. "
                + "Keep it to 1-2 sentences. Do not switch languages. "
                + "Reference text for language detection: \"" + languageHint + "\"";
        Message response = anthropicClient.messages().create(
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(128)
                        .addUserMessage(prompt)
                        .build());
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(tb -> tb.text())
                .reduce("", (a, b) -> a + b);
    }

    private ChatResponse buildDirectResponse(String clientId, String sessionId, String message) {
        return ChatResponse.builder()
                .clientId(clientId)
                .sessionId(sessionId)
                .message(message)
                .intakeComplete(false)
                .build();
    }

    private Optional<String> submitPatientLead(String profileJson, String sessionId) {
        try {
            PatientProfile profile = objectMapper.readValue(profileJson, PatientProfile.class);
            Optional<String> urn = patientLeadApiService.submitLead(profile, sessionId);
            urn.ifPresent(u -> handleIntakeCompletion(profile, u, sessionId));
            return urn;
        } catch (Exception e) {
            log.error("Failed to parse patient profile JSON for session {}: {}", sessionId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Called once per session after intake is confirmed and a URN has been assigned.
     * Orchestrates all post-intake persistence: API update and database write.
     */
    private void handleIntakeCompletion(PatientProfile profile, String urn, String sessionId) {
        patientLeadApiService.updateLead(profile, urn, sessionId);
    }
}
