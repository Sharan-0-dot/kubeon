package com.sharan.kubeon.api.dto;

import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.incident.model.Incident;
import com.sharan.kubeon.incident.model.IncidentStatus;
import com.sharan.kubeon.incident.model.TriggerType;
import com.sharan.kubeon.kubernetes.evidence.EvidenceBundle;
import com.sharan.kubeon.reasoning.Diagnosis;

import java.time.Instant;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        String namespace,
        String podName,
        BadStateReason reason,
        IncidentStatus status,
        TriggerType triggerType,
        boolean notified,
        Instant createdAt,
        Diagnosis diagnosis,
        EvidenceBundle evidence
) {
    public static IncidentResponse from(Incident incident) {
        return new IncidentResponse(
                incident.id(),
                incident.namespace(),
                incident.podName(),
                incident.issue() != null ? incident.issue().reason() : null,
                incident.status(),
                incident.triggerType(),
                incident.notified(),
                incident.createdAt(),
                incident.diagnosis(),
                incident.evidence()
        );
    }
}
