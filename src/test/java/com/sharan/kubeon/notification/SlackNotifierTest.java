package com.sharan.kubeon.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.incident.model.Incident;
import com.sharan.kubeon.incident.model.IncidentStatus;
import com.sharan.kubeon.incident.model.TriggerType;
import com.sharan.kubeon.reasoning.ConfidenceLevel;
import com.sharan.kubeon.reasoning.Diagnosis;
import com.sharan.kubeon.security.SensitiveDataRedactor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlackNotifierTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    private Incident createSampleIncident(TriggerType triggerType) {
        DetectedIssue issue = new DetectedIssue("default", "oom-pod", BadStateReason.OOM_KILLED, Instant.now(), "Terminated");
        Diagnosis diagnosis = new Diagnosis("Exceeded limit", ConfidenceLevel.HIGH, "Increase limit", List.of("getPodDetails"), "gemini-2.0-flash");
        return new Incident(UUID.randomUUID(), "default", "oom-pod", issue, null, diagnosis, IncidentStatus.DIAGNOSED, triggerType, false, Instant.now());
    }

    @Test
    void testDisabledWhenNoWebhookUrl() {
        SlackNotifier notifier = new SlackNotifier("", false, objectMapper);
        boolean result = notifier.notify(createSampleIncident(TriggerType.AUTO_DETECTED));

        assertThat(result).isFalse();
    }

    @Test
    void testManualTriggerSkippedWhenConfigured() {
        SlackNotifier notifier = new SlackNotifier("https://hooks.slack.com/services/TEST/MOCK", false, objectMapper);
        boolean result = notifier.notify(createSampleIncident(TriggerType.MANUAL));

        assertThat(result).isFalse();
    }

    @Test
    void testFailureIsolationOnInvalidWebhook() {
        // Invalid URL will cause HTTP client error, must not throw exception
        SlackNotifier notifier = new SlackNotifier("http://invalid-unresolvable-domain-12345.test/webhook", true, objectMapper);
        boolean result = notifier.notify(createSampleIncident(TriggerType.AUTO_DETECTED));

        assertThat(result).isFalse();
    }

    @Test
    void testSlackMessageFormattingAndRedaction() {
        SensitiveDataRedactor redactor = new SensitiveDataRedactor(true);
        SlackNotifier notifier = new SlackNotifier("", false, objectMapper, redactor);

        DetectedIssue issue = new DetectedIssue(
                "default", "auth-pod", BadStateReason.CRASH_LOOP_BACKOFF, Instant.now(),
                "Failed with password=plainTextPass"
        );
        Diagnosis diagnosis = new Diagnosis(
                "Failed to connect to db with uri: postgres://admin:secretPassword123@db:5432/app",
                ConfidenceLevel.HIGH,
                "Reset Authorization: Bearer secretBearer12345 in secret",
                List.of("getContainerLogs"),
                "gemini-2.0-flash"
        );
        Incident incident = new Incident(
                UUID.randomUUID(), "default", "auth-pod", issue, null, diagnosis,
                IncidentStatus.DIAGNOSED, TriggerType.AUTO_DETECTED, false, Instant.now()
        );

        String rawFormatted = notifier.formatSlackMessage(incident);
        String sanitized = redactor.redactText(rawFormatted);

        assertThat(sanitized).doesNotContain("secretPassword123");
        assertThat(sanitized).doesNotContain("secretBearer12345");
        assertThat(sanitized).contains("postgres://admin:[REDACTED]@db:5432/app");
        assertThat(sanitized).contains("Authorization: Bearer [REDACTED]");
    }
}
