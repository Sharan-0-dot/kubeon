package com.sharan.kubeon.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.incident.model.Incident;
import com.sharan.kubeon.incident.model.IncidentStatus;
import com.sharan.kubeon.incident.model.TriggerType;
import com.sharan.kubeon.reasoning.ConfidenceLevel;
import com.sharan.kubeon.reasoning.Diagnosis;
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
}
