package com.sharan.kubeon.incident.persistence;

import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.incident.model.IncidentStatus;
import com.sharan.kubeon.incident.model.TriggerType;
import com.sharan.kubeon.reasoning.ConfidenceLevel;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "incidents", indexes = {
        @Index(name = "idx_incidents_namespace", columnList = "namespace"),
        @Index(name = "idx_incidents_created_at", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String namespace;

    @Column(nullable = false)
    private String podName;

    @Enumerated(EnumType.STRING)
    private BadStateReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncidentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerType triggerType;

    private boolean notified;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant detectedAt;

    @Column(columnDefinition = "TEXT")
    private String sourceMessage;

    // Structured diagnosis fields
    @Column(columnDefinition = "TEXT")
    private String rootCauseHypothesis;

    @Enumerated(EnumType.STRING)
    private ConfidenceLevel confidence;

    @Column(columnDefinition = "TEXT")
    private String suggestedFix;

    @Column(columnDefinition = "TEXT")
    private String toolCallsUsed;

    private String modelUsed;

    // Evidence and Issue JSON payloads
    @Column(columnDefinition = "TEXT")
    private String evidenceJson;

    @Column(columnDefinition = "TEXT")
    private String issueJson;
}
