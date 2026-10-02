package com.sharan.kubeon.incident.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.incident.model.Incident;
import com.sharan.kubeon.incident.model.IncidentStatus;
import com.sharan.kubeon.incident.repository.IncidentRepository;
import com.sharan.kubeon.kubernetes.evidence.EvidenceBundle;
import com.sharan.kubeon.reasoning.Diagnosis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class PostgresIncidentRepository implements IncidentRepository {

    private static final Logger log = LoggerFactory.getLogger(PostgresIncidentRepository.class);

    private final IncidentJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;

    public PostgresIncidentRepository(IncidentJpaRepository jpaRepository, ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Incident save(Incident incident) {
        IncidentEntity entity = toEntity(incident);
        IncidentEntity saved = jpaRepository.save(entity);
        return toDomain(saved);
    }

    @Override
    public Optional<Incident> findById(UUID id) {
        return jpaRepository.findById(id).map(this::toDomain);
    }

    @Override
    public List<Incident> findAll() {
        return jpaRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Incident> findByNamespace(String namespace) {
        return jpaRepository.findByNamespaceOrderByCreatedAtDesc(namespace).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public boolean hasActiveIncident(String namespace, String podName) {
        return jpaRepository.findTopByNamespaceAndPodNameAndStatusNotOrderByCreatedAtDesc(
                namespace, podName, IncidentStatus.RESOLVED).isPresent();
    }

    private IncidentEntity toEntity(Incident incident) {
        String evidenceJson = null;
        if (incident.evidence() != null) {
            try {
                evidenceJson = objectMapper.writeValueAsString(incident.evidence());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize evidence bundle for incident {}: {}", incident.id(), e.getMessage());
            }
        }

        String issueJson = null;
        if (incident.issue() != null) {
            try {
                issueJson = objectMapper.writeValueAsString(incident.issue());
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize issue for incident {}: {}", incident.id(), e.getMessage());
            }
        }

        String toolCallsJson = null;
        if (incident.diagnosis() != null && incident.diagnosis().toolCallsUsed() != null) {
            try {
                toolCallsJson = objectMapper.writeValueAsString(incident.diagnosis().toolCallsUsed());
            } catch (JsonProcessingException ignored) {}
        }

        return IncidentEntity.builder()
                .id(incident.id())
                .namespace(incident.namespace())
                .podName(incident.podName())
                .reason(incident.issue() != null ? incident.issue().reason() : null)
                .status(incident.status())
                .triggerType(incident.triggerType())
                .notified(incident.notified())
                .createdAt(incident.createdAt())
                .detectedAt(incident.issue() != null ? incident.issue().detectedAt() : null)
                .sourceMessage(incident.issue() != null ? incident.issue().sourceMessage() : null)
                .rootCauseHypothesis(incident.diagnosis() != null ? incident.diagnosis().rootCauseHypothesis() : null)
                .confidence(incident.diagnosis() != null ? incident.diagnosis().confidence() : null)
                .suggestedFix(incident.diagnosis() != null ? incident.diagnosis().suggestedFix() : null)
                .toolCallsUsed(toolCallsJson)
                .modelUsed(incident.diagnosis() != null ? incident.diagnosis().modelUsed() : null)
                .evidenceJson(evidenceJson)
                .issueJson(issueJson)
                .build();
    }

    private Incident toDomain(IncidentEntity entity) {
        EvidenceBundle evidence = null;
        if (entity.getEvidenceJson() != null && !entity.getEvidenceJson().isBlank()) {
            try {
                evidence = objectMapper.readValue(entity.getEvidenceJson(), EvidenceBundle.class);
            } catch (Exception e) {
                log.warn("Failed to deserialize evidence JSON for incident {}: {}", entity.getId(), e.getMessage());
            }
        }

        DetectedIssue issue = null;
        if (entity.getIssueJson() != null && !entity.getIssueJson().isBlank()) {
            try {
                issue = objectMapper.readValue(entity.getIssueJson(), DetectedIssue.class);
            } catch (Exception ignored) {}
        }
        if (issue == null) {
            issue = new DetectedIssue(
                    entity.getNamespace(),
                    entity.getPodName(),
                    entity.getReason(),
                    entity.getDetectedAt() != null ? entity.getDetectedAt() : entity.getCreatedAt(),
                    entity.getSourceMessage()
            );
        }

        Diagnosis diagnosis = null;
        if (entity.getRootCauseHypothesis() != null) {
            List<String> tools = Collections.emptyList();
            if (entity.getToolCallsUsed() != null && !entity.getToolCallsUsed().isBlank()) {
                try {
                    tools = objectMapper.readValue(entity.getToolCallsUsed(), new TypeReference<List<String>>() {});
                } catch (Exception ignored) {}
            }

            diagnosis = new Diagnosis(
                    entity.getRootCauseHypothesis(),
                    entity.getConfidence(),
                    entity.getSuggestedFix(),
                    tools,
                    entity.getModelUsed()
            );
        }

        return new Incident(
                entity.getId(),
                entity.getNamespace(),
                entity.getPodName(),
                issue,
                evidence,
                diagnosis,
                entity.getStatus(),
                entity.getTriggerType(),
                entity.isNotified(),
                entity.getCreatedAt()
        );
    }
}
