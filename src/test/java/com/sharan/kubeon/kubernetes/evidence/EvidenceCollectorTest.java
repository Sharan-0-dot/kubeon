package com.sharan.kubeon.kubernetes.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvidenceCollectorTest {

    @Mock private KubernetesClient client;
    @Mock private MixedOperation<Pod, PodList, PodResource> podOperation;
    @Mock private NonNamespaceOperation<Pod, PodList, PodResource> namedPodOperation;
    @Mock private PodResource podResource;
    @Mock private ContainerResource containerResource;
    @Mock private TimeTailPrettyLoggable terminatedLoggable;

    @Mock private V1APIGroupDSL v1;
    @Mock private MixedOperation<Event, EventList, Resource<Event>> eventOperation;
    @Mock private NonNamespaceOperation<Event, EventList, Resource<Event>> namedEventOperation;

    private ObjectMapper objectMapper;
    private EvidenceCollector collector;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        collector = new EvidenceCollector(client, objectMapper);

        when(client.pods()).thenReturn(podOperation);
        when(podOperation.inNamespace(anyString())).thenReturn(namedPodOperation);
        when(namedPodOperation.withName(anyString())).thenReturn(podResource);
        when(podResource.inContainer(anyString())).thenReturn(containerResource);
        when(containerResource.terminated()).thenReturn(terminatedLoggable);
        when(terminatedLoggable.tailingLines(anyInt())).thenReturn(terminatedLoggable);
        when(containerResource.tailingLines(anyInt())).thenReturn(containerResource);

        when(client.v1()).thenReturn(v1);
        when(v1.events()).thenReturn(eventOperation);
        when(eventOperation.inNamespace(anyString())).thenReturn(namedEventOperation);
        when(namedEventOperation.list()).thenReturn(new EventListBuilder().build());
    }

    @Test
    void testSuccessfulEvidenceCollection() {
        // 1. Setup Pod
        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withNamespace("default")
                    .withName("crash-pod")
                    .withLabels(Map.of("app", "demo", "env", "prod"))
                .endMetadata()
                .withNewSpec()
                    .withNodeName("minikube-worker")
                    .withRestartPolicy("Always")
                    .addNewContainer()
                        .withName("main-app")
                        .withImage("demo/app:1.0")
                        .withNewResources()
                            .addToRequests("cpu", new Quantity("100m"))
                            .addToRequests("memory", new Quantity("128Mi"))
                            .addToLimits("cpu", new Quantity("500m"))
                            .addToLimits("memory", new Quantity("256Mi"))
                        .endResources()
                        .withNewLivenessProbe().endLivenessProbe()
                        .withNewReadinessProbe().endReadinessProbe()
                    .endContainer()
                .endSpec()
                .withNewStatus()
                    .withPhase("Running")
                .endStatus()
                .build();

        when(podResource.get()).thenReturn(pod);

        // 2. Setup Events
        Event event = new EventBuilder()
                .withNewMetadata().withName("event-1").endMetadata()
                .withNewInvolvedObject()
                    .withKind("Pod")
                    .withName("crash-pod")
                    .withNamespace("default")
                .endInvolvedObject()
                .withType("Warning")
                .withReason("BackOff")
                .withMessage("Back-off restarting failed container")
                .withCount(5)
                .withLastTimestamp("2026-10-01T10:00:00Z")
                .withNewSource().withComponent("kubelet").endSource()
                .build();

        EventList eventList = new EventListBuilder().withItems(event).build();
        when(namedEventOperation.list()).thenReturn(eventList);

        // 3. Setup Logs (previous logs return content)
        when(terminatedLoggable.getLog()).thenReturn("Exception in thread \"main\" java.lang.OutOfMemoryError\nStack trace line 1");

        DetectedIssue issue = new DetectedIssue(
                "default",
                "crash-pod",
                BadStateReason.CRASH_LOOP_BACKOFF,
                Instant.now(),
                "Pod backoff"
        );

        EvidenceBundle bundle = collector.collect(issue);

        assertThat(bundle).isNotNull();
        assertThat(bundle.issue()).isEqualTo(issue);
        assertThat(bundle.podSpec()).isNotNull();
        assertThat(bundle.podSpec().podName()).isEqualTo("crash-pod");
        assertThat(bundle.podSpec().namespace()).isEqualTo("default");
        assertThat(bundle.podSpec().nodeName()).isEqualTo("minikube-worker");
        assertThat(bundle.podSpec().phase()).isEqualTo("Running");
        assertThat(bundle.podSpec().restartPolicy()).isEqualTo("Always");
        assertThat(bundle.podSpec().labels()).containsEntry("app", "demo");

        // Container assertion
        assertThat(bundle.podSpec().containers()).hasSize(1);
        ContainerSummary cs = bundle.podSpec().containers().get(0);
        assertThat(cs.name()).isEqualTo("main-app");
        assertThat(cs.image()).isEqualTo("demo/app:1.0");
        assertThat(cs.requests()).containsEntry("cpu", "100m").containsEntry("memory", "128Mi");
        assertThat(cs.limits()).containsEntry("cpu", "500m").containsEntry("memory", "256Mi");
        assertThat(cs.hasLivenessProbe()).isTrue();
        assertThat(cs.hasReadinessProbe()).isTrue();
        assertThat(cs.hasStartupProbe()).isFalse();
        assertThat(cs.isInit()).isFalse();

        // Events assertion
        assertThat(bundle.recentEvents()).hasSize(1);
        EventSummary es = bundle.recentEvents().get(0);
        assertThat(es.type()).isEqualTo("Warning");
        assertThat(es.reason()).isEqualTo("BackOff");
        assertThat(es.count()).isEqualTo(5);
        assertThat(es.sourceComponent()).isEqualTo("kubelet");

        // Logs assertion
        assertThat(bundle.logs()).hasSize(1);
        LogSnapshot ls = bundle.logs().get(0);
        assertThat(ls.containerName()).isEqualTo("main-app");
        assertThat(ls.previous()).isTrue();
        assertThat(ls.logContent()).contains("OutOfMemoryError");
        assertThat(ls.truncated()).isFalse();
        assertThat(ls.errorMessage()).isNull();
    }

    @Test
    void testMultipleContainersAndInitContainers() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("multi-pod").withNamespace("default").endMetadata()
                .withNewSpec()
                    .addNewInitContainer()
                        .withName("init-db")
                        .withImage("migrate:1.0")
                    .endInitContainer()
                    .addNewContainer()
                        .withName("web")
                        .withImage("nginx:latest")
                    .endContainer()
                    .addNewContainer()
                        .withName("sidecar")
                        .withImage("envoy:1.20")
                    .endContainer()
                .endSpec()
                .build();

        when(podResource.get()).thenReturn(pod);

        EvidenceBundle bundle = collector.collect("default", "multi-pod");

        assertThat(bundle.podSpec().containers()).hasSize(3);
        assertThat(bundle.podSpec().containers().get(0).name()).isEqualTo("init-db");
        assertThat(bundle.podSpec().containers().get(0).isInit()).isTrue();
        assertThat(bundle.podSpec().containers().get(1).name()).isEqualTo("web");
        assertThat(bundle.podSpec().containers().get(1).isInit()).isFalse();
        assertThat(bundle.podSpec().containers().get(2).name()).isEqualTo("sidecar");
        assertThat(bundle.podSpec().containers().get(2).isInit()).isFalse();
    }

    @Test
    void testMissingPreviousLogsFallsBackToCurrentLogs() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("fallback-pod").withNamespace("default").endMetadata()
                .withNewSpec()
                    .addNewContainer().withName("app").withImage("app:1.0").endContainer()
                .endSpec()
                .build();

        when(podResource.get()).thenReturn(pod);

        // Previous logs fail (throw KubernetesClientException)
        when(terminatedLoggable.getLog())
                .thenThrow(new KubernetesClientException("previous terminated container not found"));

        // Current logs succeed
        when(containerResource.getLog()).thenReturn("Application started on port 8080");

        EvidenceBundle bundle = collector.collect("default", "fallback-pod");

        assertThat(bundle.logs()).hasSize(1);
        LogSnapshot ls = bundle.logs().get(0);
        assertThat(ls.containerName()).isEqualTo("app");
        assertThat(ls.previous()).isFalse();
        assertThat(ls.logContent()).isEqualTo("Application started on port 8080");
        assertThat(ls.errorMessage()).isNull();
    }

    @Test
    void testPodNotFoundGracefulHandling() {
        when(podResource.get()).thenReturn(null);

        // Event for deleted pod exists in namespace
        Event event = new EventBuilder()
                .withNewInvolvedObject().withKind("Pod").withName("deleted-pod").endInvolvedObject()
                .withReason("Killing")
                .withMessage("Stopping container")
                .build();
        when(namedEventOperation.list()).thenReturn(new EventListBuilder().withItems(event).build());

        DetectedIssue issue = new DetectedIssue(
                "default",
                "deleted-pod",
                BadStateReason.CRASH_LOOP_BACKOFF,
                Instant.now(),
                "Pod died"
        );

        EvidenceBundle bundle = collector.collect(issue);

        assertThat(bundle).isNotNull();
        assertThat(bundle.podSpec()).isNull();
        assertThat(bundle.logs()).isEmpty();
        assertThat(bundle.recentEvents()).hasSize(1);
        assertThat(bundle.recentEvents().get(0).reason()).isEqualTo("Killing");
    }

    @Test
    void testMissingResourceConfigurationAndEvents() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("bare-pod").withNamespace("default").endMetadata()
                .withNewSpec()
                    .addNewContainer()
                        .withName("bare")
                        .withImage("alpine")
                    .endContainer()
                .endSpec()
                .build();

        when(podResource.get()).thenReturn(pod);
        when(namedEventOperation.list()).thenReturn(new EventListBuilder().build());

        EvidenceBundle bundle = collector.collect("default", "bare-pod");

        assertThat(bundle.podSpec()).isNotNull();
        ContainerSummary cs = bundle.podSpec().containers().get(0);
        assertThat(cs.requests()).isEmpty();
        assertThat(cs.limits()).isEmpty();
        assertThat(bundle.recentEvents()).isEmpty();
    }

    @Test
    void testKubernetesApiFailureDoesNotCrashCollector() {
        when(podResource.get()).thenThrow(new KubernetesClientException("Internal Server Error"));
        when(namedEventOperation.list()).thenThrow(new KubernetesClientException("Forbidden"));

        DetectedIssue issue = new DetectedIssue(
                "default",
                "error-pod",
                BadStateReason.FAILED_SCHEDULING,
                Instant.now(),
                "API failure test"
        );

        EvidenceBundle bundle = collector.collect(issue);

        assertThat(bundle).isNotNull();
        assertThat(bundle.podSpec()).isNull();
        assertThat(bundle.recentEvents()).isEmpty();
        assertThat(bundle.logs()).isEmpty();
    }
}
