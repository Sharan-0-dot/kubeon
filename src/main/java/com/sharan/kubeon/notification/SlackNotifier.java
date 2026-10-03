package com.sharan.kubeon.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.incident.model.Incident;
import com.sharan.kubeon.security.SensitiveDataRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class SlackNotifier {

    private static final Logger log = LoggerFactory.getLogger(SlackNotifier.class);

    private final String webhookUrl;
    private final boolean notifyOnManual;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SensitiveDataRedactor sensitiveDataRedactor;

    public SlackNotifier(String webhookUrl,
                         boolean notifyOnManual,
                         ObjectMapper objectMapper) {
        this(webhookUrl, notifyOnManual, objectMapper, new SensitiveDataRedactor(true));
    }

    @Autowired
    public SlackNotifier(@Value("${kubeon.slack.webhook-url:${SLACK_WEBHOOK_URL:}}") String webhookUrl,
                         @Value("${kubeon.slack.notify-on-manual:false}") boolean notifyOnManual,
                         ObjectMapper objectMapper,
                         @Autowired(required = false) SensitiveDataRedactor sensitiveDataRedactor) {
        this.webhookUrl = webhookUrl != null ? webhookUrl.trim() : "";
        this.notifyOnManual = notifyOnManual;
        this.objectMapper = objectMapper;
        this.sensitiveDataRedactor = sensitiveDataRedactor != null ? sensitiveDataRedactor : new SensitiveDataRedactor(true);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean notify(Incident incident) {
        if (webhookUrl.isBlank()) {
            log.debug("Slack webhook URL not configured. Skipping Slack notification for incident {}", incident.id());
            return false;
        }

        if (incident.triggerType() != null && incident.triggerType().name().equals("MANUAL") && !notifyOnManual) {
            log.debug("Manual incident notification disabled by configuration for incident {}", incident.id());
            return false;
        }

        try {
            String rawMessage = formatSlackMessage(incident);
            String sanitizedMessage = sensitiveDataRedactor.redactText(rawMessage);
            Map<String, String> payload = Map.of("text", sanitizedMessage);
            String jsonPayload = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("Slack notification sent successfully for incident {} ({}/{})",
                        incident.id(), incident.namespace(), incident.podName());
                return true;
            } else {
                log.warn("Slack webhook returned HTTP {} for incident {}: {}",
                        response.statusCode(), incident.id(), response.body());
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to deliver Slack notification for incident {}: {}", incident.id(), e.getMessage());
            return false;
        }
    }

    String formatSlackMessage(Incident incident) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🚨 *Kubeon Incident Alert*\n"));
        sb.append(String.format("*Namespace:* `%s` | *Pod:* `%s`\n", incident.namespace(), incident.podName()));
        sb.append(String.format("*Reason:* `%s` | *Status:* `%s` | *Trigger:* `%s`\n",
                incident.issue() != null ? incident.issue().reason() : "N/A",
                incident.status(),
                incident.triggerType()));

        if (incident.diagnosis() != null) {
            sb.append(String.format("\n*Confidence:* %s\n", incident.diagnosis().confidence()));
            sb.append(String.format("*Root Cause:*\n%s\n", incident.diagnosis().rootCauseHypothesis()));
            sb.append(String.format("\n*Suggested Fix:*\n%s\n", incident.diagnosis().suggestedFix()));

            if (incident.diagnosis().toolCallsUsed() != null && !incident.diagnosis().toolCallsUsed().isEmpty()) {
                sb.append(String.format("\n*Investigation Tools Used:* `%s`\n",
                        String.join(", ", incident.diagnosis().toolCallsUsed())));
            }
        } else {
            sb.append("\n_Diagnosis pending or not available._\n");
        }

        sb.append(String.format("\n*Incident ID:* `%s`", incident.id()));
        return sb.toString();
    }
}
