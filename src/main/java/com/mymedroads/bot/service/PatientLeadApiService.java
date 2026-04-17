package com.mymedroads.bot.service;

import com.mymedroads.bot.model.PatientProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class PatientLeadApiService {

    private final RestClient restClient;

    @Value("${mymedroads-api-suite.url}")
    private String apiUrl;

    public PatientLeadApiService(RestClient.Builder builder) {
        this.restClient = builder.build();
    }

    @SuppressWarnings("null")
    public void submitLead(PatientProfile profile, String sessionId) {
        if (apiUrl == null || apiUrl.isBlank()) {
            log.warn("mymedroads-api-suite.url is not configured — skipping lead submission for session {}", sessionId);
            return;
        }

        apiUrl = apiUrl.endsWith("/") ? apiUrl + "submitlead?channel=chatbot" : apiUrl + "/submitlead?channel=chatbot";
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
