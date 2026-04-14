package com.mymedroads.bot.service;

import com.mymedroads.bot.model.PatientProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Submits a captured patient intake profile to the external CRM / lead API.
 * Configure the target URL via the PATIENT_LEAD_API_URL environment variable.
 */
@Slf4j
@Service
public class PatientLeadApiService {

    private final RestClient restClient;

    @Value("${patient-lead.api.url}")
    private String apiUrl;

    public PatientLeadApiService(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    /**
     * POST the confirmed patient profile to the external lead API.
     * Errors are caught and logged so that a downstream failure never disrupts the chat response.
     */
    @SuppressWarnings("null") // apiUrl is guarded non-blank above; APPLICATION_JSON is a non-null constant
    public void submitLead(PatientProfile profile, String sessionId) {
        if (apiUrl == null || apiUrl.isBlank()) {
            log.warn("patient-lead.api.url is not configured — skipping lead submission for session {}", sessionId);
            return;
        }

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
                    .uri(apiUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Patient lead submitted successfully for session: {}", sessionId);

        } catch (Exception e) {
            log.error("Failed to submit patient lead for session {}: {}", sessionId, e.getMessage(), e);
        }
    }
}
