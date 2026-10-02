package com.sharan.kubeon.incident.service;

import com.sharan.kubeon.api.dto.HealthCheckResult;
import com.sharan.kubeon.api.exception.ResourceNotFoundException;
import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.incident.model.Incident;
import com.sharan.kubeon.incident.model.IncidentStatus;
import com.sharan.kubeon.incident.model.TriggerType;
import com.sharan.kubeon.incident.repository.IncidentRepository;
import com.sharan.kubeon.kubernetes.evidence.EvidenceBundle;
import com.sharan.kubeon.kubernetes.evidence.EvidenceCollector;
import com.sharan.kubeon.kubernetes.watcher.PodWatcher;
import com.sharan.kubeon.notification.SlackNotifier;
import com.sharan.kubeon.reasoning.ConfidenceLevel;
import com.sharan.kubeon.reasoning.Diagnosis;
import com.sharan.kubeon.reasoning.ReasoningAgent;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.PodListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentServiceTest {

    @Mock private IncidentRepository repository;
    @Mock private EvidenceCollector evidenceCollector;
    @Mock private ReasoningAgent reasoningAgent;
    @Mock private SlackNotifier slackNotifier;
    @Mock private KubernetesClient kubernetesClient;
    @Mock private PodWatcher podWatcher;

    @Mock private MixedOperation<Pod, PodList, PodResource> podOperation;
    @Mock private NonNamespaceOperation<Pod, PodList, PodResource> namedPodOperation;
    @Mock private PodResource podResource;

    private IncidentService service;

    @BeforeEach
    void setUp() {
        service = new IncidentService(
                repository,
                evidenceCollector,
                reasoningAgent,
                slackNotifier,
                kubernetesClient,
                podWatcher
        );

        when(kubernetesClient.pods()).thenReturn(podOperation);
        when(podOperation.inNamespace(anyString())).thenReturn(namedPodOperation);
        when(namedPodOperation.withName(anyString())).thenReturn(podResource);
        when(repository.save(any(Incident.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void testProcessDetectedIssueSuccess() {
        DetectedIssue issue = new DetectedIssue(
                "default", "oom-pod", BadStateReason.OOM_KILLED, Instant.now(), "OOMKilled detected");
        EvidenceBundle bundle = new EvidenceBundle(issue, null, List.of(), List.of(), Instant.now());
        Diagnosis diagnosis = new Diagnosis(
                "Exceeded memory limit", ConfidenceLevel.HIGH, "Increase limit", List.of(), "gemini-2.0-flash");

        when(evidenceCollector.collect(issue)).thenReturn(bundle);
        when(reasoningAgent.diagnose(bundle)).thenReturn(diagnosis);
        when(slackNotifier.notify(any())).thenReturn(true);

        Incident incident = service.processDetectedIssue(issue);

        assertThat(incident).isNotNull();
        assertThat(incident.namespace()).isEqualTo("default");
        assertThat(incident.podName()).isEqualTo("oom-pod");
        assertThat(incident.status()).isEqualTo(IncidentStatus.DIAGNOSED);
        assertThat(incident.triggerType()).isEqualTo(TriggerType.AUTO_DETECTED);
        assertThat(incident.diagnosis()).isEqualTo(diagnosis);
        assertThat(incident.notified()).isTrue();

        verify(repository, atLeast(2)).save(any(Incident.class));
        verify(slackNotifier).notify(any(Incident.class));
    }

    @Test
    void testInvestigateManualSuccess() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("test-pod").withNamespace("prod").endMetadata()
                .build();
        when(podResource.get()).thenReturn(pod);

        DetectedIssue detected = new DetectedIssue("prod", "test-pod", BadStateReason.CRASH_LOOP_BACKOFF, Instant.now(), "Crash");
        when(podWatcher.detect(pod)).thenReturn(List.of(detected));

        EvidenceBundle bundle = new EvidenceBundle(detected, null, List.of(), List.of(), Instant.now());
        Diagnosis diagnosis = new Diagnosis("App crashed", ConfidenceLevel.MEDIUM, "Fix config", List.of("getPodDetails"), "gemini-2.0-flash");

        when(evidenceCollector.collect(any())).thenReturn(bundle);
        when(reasoningAgent.diagnose(bundle)).thenReturn(diagnosis);
        when(slackNotifier.notify(any())).thenReturn(false);

        Incident incident = service.investigate("prod", "test-pod");

        assertThat(incident).isNotNull();
        assertThat(incident.namespace()).isEqualTo("prod");
        assertThat(incident.podName()).isEqualTo("test-pod");
        assertThat(incident.status()).isEqualTo(IncidentStatus.DIAGNOSED);
        assertThat(incident.triggerType()).isEqualTo(TriggerType.MANUAL);
        assertThat(incident.diagnosis().toolCallsUsed()).contains("getPodDetails");
    }

    @Test
    void testInvestigatePodNotFoundThrowsException() {
        when(podResource.get()).thenReturn(null);

        assertThatThrownBy(() -> service.investigate("default", "missing-pod"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("missing-pod");
    }

    @Test
    void testInvestigateInvalidArguments() {
        assertThatThrownBy(() -> service.investigate(null, "pod"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.investigate("default", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testInvestigationFailureHandledGracefully() {
        DetectedIssue issue = new DetectedIssue("default", "err-pod", BadStateReason.CRASH_LOOP_BACKOFF, Instant.now(), "Fail");
        EvidenceBundle bundle = new EvidenceBundle(issue, null, List.of(), List.of(), Instant.now());

        when(evidenceCollector.collect(issue)).thenReturn(bundle);
        when(reasoningAgent.diagnose(bundle)).thenThrow(new RuntimeException("Gemini quota exceeded"));

        Incident incident = service.processDetectedIssue(issue);

        assertThat(incident).isNotNull();
        assertThat(incident.status()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(incident.diagnosis().rootCauseHypothesis()).contains("Investigation failed");
        verify(repository, atLeast(1)).save(any(Incident.class));
    }

    @Test
    void testRunHealthCheckOk() {
        Pod pod = new PodBuilder().withNewMetadata().withName("healthy").endMetadata().build();
        PodList podList = new PodListBuilder().withItems(pod).build();
        when(podOperation.inAnyNamespace()).thenReturn(namedPodOperation);
        when(namedPodOperation.list()).thenReturn(podList);
        when(podWatcher.detect(pod)).thenReturn(List.of());

        HealthCheckResult result = service.runHealthCheck();

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.checkedPodCount()).isEqualTo(1);
        assertThat(result.issueCount()).isEqualTo(0);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void testRunHealthCheckIssuesFound() {
        Pod pod = new PodBuilder().withNewMetadata().withName("failing").endMetadata().build();
        PodList podList = new PodListBuilder().withItems(pod).build();
        when(podOperation.inAnyNamespace()).thenReturn(namedPodOperation);
        when(namedPodOperation.list()).thenReturn(podList);

        DetectedIssue issue = new DetectedIssue("default", "failing", BadStateReason.OOM_KILLED, Instant.now(), "OOM");
        when(podWatcher.detect(pod)).thenReturn(List.of(issue));

        HealthCheckResult result = service.runHealthCheck();

        assertThat(result.status()).isEqualTo("ISSUES_FOUND");
        assertThat(result.checkedPodCount()).isEqualTo(1);
        assertThat(result.issueCount()).isEqualTo(1);
        assertThat(result.issues()).hasSize(1);
    }

    @Test
    void testGetAndListIncidents() {
        UUID id = UUID.randomUUID();
        Incident incident = new Incident(id, "default", "pod-1", null, null, null, IncidentStatus.DIAGNOSED, TriggerType.AUTO_DETECTED, true, Instant.now());

        when(repository.findById(id)).thenReturn(Optional.of(incident));
        when(repository.findAll()).thenReturn(List.of(incident));
        when(repository.findByNamespace("default")).thenReturn(List.of(incident));

        assertThat(service.getIncident(id)).contains(incident);
        assertThat(service.listIncidents(null)).containsExactly(incident);
        assertThat(service.listIncidents("default")).containsExactly(incident);
    }
}
