package com.sharan.kubeon.incident.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.incident.model.Incident;
import com.sharan.kubeon.incident.model.IncidentStatus;
import com.sharan.kubeon.incident.model.TriggerType;
import com.sharan.kubeon.kubernetes.evidence.EvidenceBundle;
import com.sharan.kubeon.reasoning.ConfidenceLevel;
import com.sharan.kubeon.reasoning.Diagnosis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "kubeon.watcher.enabled=false")
@Transactional
class PostgresIncidentRepositoryTest {

    @Autowired
    private IncidentJpaRepository jpaRepository;

    private PostgresIncidentRepository repository;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jpaRepository.deleteAll();
        objectMapper = new ObjectMapper().findAndRegisterModules();
        repository = new PostgresIncidentRepository(jpaRepository, objectMapper);
    }

    @Test
    void testSaveAndFindById() {
        UUID id = UUID.randomUUID();
        DetectedIssue issue = new DetectedIssue("default", "oom-pod", BadStateReason.OOM_KILLED, Instant.now(), "Memory exceeded");
        Diagnosis diagnosis = new Diagnosis("Out of memory", ConfidenceLevel.HIGH, "Increase limit", List.of("getPodDetails"), "gemini-2.5-flash");
        EvidenceBundle evidence = new EvidenceBundle(issue, null, List.of(), List.of(), Instant.now());

        Incident incident = new Incident(
                id, "default", "oom-pod", issue, evidence, diagnosis,
                IncidentStatus.DIAGNOSED, TriggerType.AUTO_DETECTED, true, Instant.now()
        );

        Incident saved = repository.save(incident);
        assertThat(saved).isNotNull();

        Optional<Incident> found = repository.findById(id);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().namespace()).isEqualTo("default");
        assertThat(found.get().podName()).isEqualTo("oom-pod");
        assertThat(found.get().status()).isEqualTo(IncidentStatus.DIAGNOSED);
        assertThat(found.get().triggerType()).isEqualTo(TriggerType.AUTO_DETECTED);
        assertThat(found.get().notified()).isTrue();
        assertThat(found.get().diagnosis()).isNotNull();
        assertThat(found.get().diagnosis().rootCauseHypothesis()).isEqualTo("Out of memory");
        assertThat(found.get().diagnosis().confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(found.get().diagnosis().toolCallsUsed()).containsExactly("getPodDetails");
        assertThat(found.get().evidence()).isNotNull();
    }

    @Test
    void testFindAllAndFindByNamespace() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        Incident inc1 = new Incident(id1, "default", "pod-1", null, null, null, IncidentStatus.DETECTED, TriggerType.AUTO_DETECTED, false, Instant.now().minusSeconds(60));
        Incident inc2 = new Incident(id2, "kube-system", "pod-2", null, null, null, IncidentStatus.DIAGNOSED, TriggerType.MANUAL, false, Instant.now());

        repository.save(inc1);
        repository.save(inc2);

        List<Incident> all = repository.findAll();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).id()).isEqualTo(id2); // Newest first

        List<Incident> defaultOnly = repository.findByNamespace("default");
        assertThat(defaultOnly).hasSize(1);
        assertThat(defaultOnly.get(0).id()).isEqualTo(id1);
    }

    @Test
    void testHasActiveIncident() {
        UUID id = UUID.randomUUID();
        Incident active = new Incident(id, "default", "active-pod", null, null, null, IncidentStatus.INVESTIGATING, TriggerType.AUTO_DETECTED, false, Instant.now());
        repository.save(active);

        assertThat(repository.hasActiveIncident("default", "active-pod")).isTrue();

        Incident resolved = active.withStatus(IncidentStatus.RESOLVED);
        repository.save(resolved);

        assertThat(repository.hasActiveIncident("default", "active-pod")).isFalse();
    }
}
