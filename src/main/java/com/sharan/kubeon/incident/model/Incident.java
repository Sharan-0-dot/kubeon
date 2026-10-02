package com.sharan.kubeon.incident.model;

import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.kubernetes.evidence.EvidenceBundle;
import com.sharan.kubeon.reasoning.Diagnosis;

import java.time.Instant;
import java.util.UUID;

public record Incident(
        UUID id,
        String namespace,
        String podName,
        DetectedIssue issue,
        EvidenceBundle evidence,
        Diagnosis diagnosis,
        IncidentStatus status,
        TriggerType triggerType,
        boolean notified,
        Instant createdAt
) {
    public static Incident create(DetectedIssue issue, TriggerType triggerType) {
        return new Incident(
                UUID.randomUUID(),
                issue.namespace(),
                issue.podName(),
                issue,
                null,
                null,
                IncidentStatus.DETECTED,
                triggerType,
                false,
                Instant.now()
        );
    }

    public Incident withEvidence(EvidenceBundle evidence, IncidentStatus status) {
        return new Incident(id, namespace, podName, issue, evidence, diagnosis, status, triggerType, notified, createdAt);
    }

    public Incident withDiagnosis(Diagnosis diagnosis, IncidentStatus status) {
        return new Incident(id, namespace, podName, issue, evidence, diagnosis, status, triggerType, notified, createdAt);
    }

    public Incident withNotified(boolean notified) {
        return new Incident(id, namespace, podName, issue, evidence, diagnosis, status, triggerType, notified, createdAt);
    }

    public Incident withStatus(IncidentStatus status) {
        return new Incident(id, namespace, podName, issue, evidence, diagnosis, status, triggerType, notified, createdAt);
    }
}
