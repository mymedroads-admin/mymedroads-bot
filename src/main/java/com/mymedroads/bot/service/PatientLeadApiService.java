package com.mymedroads.bot.service;

import com.mymedroads.bot.model.PatientProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class PatientLeadApiService {

    private final RestClient restClient;

    @Value("${mymedroads-api-suite.url}")
    private String apiUrl;

    public PatientLeadApiService(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @SuppressWarnings({"null", "unchecked"})
    public Optional<String> submitLead(PatientProfile profile, String sessionId) {
        if (apiUrl == null || apiUrl.isBlank()) {
            log.warn("mymedroads-api-suite.url is not configured — skipping lead submission for session {}", sessionId);
            return Optional.empty();
        }

        String baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        try {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("name", profile.getName());
            payload.put("age", profile.getAge());
            payload.put("gender", profile.getGender());
            payload.put("mobile", profile.getMobile());
            payload.put("email", profile.getEmail());
            payload.put("destination", profile.getDestination());
            payload.put("medicalIssue", profile.getMedicalIssue());

            restClient.post()
                    .uri(baseUrl + "/submitlead?channel=chatbot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Patient lead submitted successfully for session: {}", sessionId);

            Map<String, Object> refResponse = restClient.get()
                    .uri(baseUrl + "/geturn?sessionId=" + sessionId)
                    .retrieve()
                    .body(Map.class);

            if (refResponse != null && refResponse.containsKey("urn")) {
                String urn = refResponse.get("urn").toString();
                log.info("URN {} fetched for session: {}", urn, sessionId);
                return Optional.of(urn);
            }
        } catch (Exception e) {
            log.error("Failed to submit patient lead for session {}: {}", sessionId, e.getMessage(), e);
        }
        return Optional.empty();
    }

    @SuppressWarnings({"null", "unchecked"})
    public Map<String, Object> fetchCaseStatus(String urn) {
        if (apiUrl == null || apiUrl.isBlank()) {
            log.warn("mymedroads-api-suite.url is not configured — skipping case status fetch for URN {}", urn);
            return Map.of("error", "Service not configured");
        }

        String baseUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
        try {
            Map<String, Object> response = restClient.get()
                    .uri(baseUrl + "/getcase?urn=" + urn)
                    .retrieve()
                    .body(Map.class);

            if (response != null) {
                log.info("Case status fetched for URN: {}", urn);
                return response;
            }
        } catch (Exception e) {
            log.error("Failed to fetch case status for URN {}: {}", urn, e.getMessage(), e);
        }
        return Map.of("error", "Unable to fetch case status");
    }
}
