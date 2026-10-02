package com.sharan.kubeon.agent.tools;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KubernetesEvidenceToolsTest {

    @Mock private KubernetesClient client;
    @Mock private MixedOperation<Pod, PodList, PodResource> podOperation;
    @Mock private NonNamespaceOperation<Pod, PodList, PodResource> namedPodOperation;
    @Mock private PodResource podResource;
    @Mock private ContainerResource containerResource;
    @Mock private TimeTailPrettyLoggable terminatedLoggable;

    @Mock private V1APIGroupDSL v1;
    @Mock private MixedOperation<Event, EventList, Resource<Event>> eventOperation;
    @Mock private NonNamespaceOperation<Event, EventList, Resource<Event>> namedEventOperation;
    @Mock private FilterWatchListDeletable<Event, EventList, Resource<Event>> filterWatchListDeletable;

    @Mock private AppsAPIGroupDSL apps;
    @Mock private MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> deploymentOperation;
    @Mock private NonNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>> namedDeploymentOperation;
    @Mock private RollableScalableResource<Deployment> deploymentResource;

    private KubernetesEvidenceTools tools;

    @BeforeEach
    void setUp() {
        tools = new KubernetesEvidenceTools(client);

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

        when(client.apps()).thenReturn(apps);
        when(apps.deployments()).thenReturn(deploymentOperation);
        when(deploymentOperation.inNamespace(anyString())).thenReturn(namedDeploymentOperation);
        when(namedDeploymentOperation.withName(anyString())).thenReturn(deploymentResource);
    }

    @Test
    void testGetPodDetailsValidation() {
        assertThat(tools.getPodDetails(null, "pod1")).contains("Error: namespace must not be empty");
        assertThat(tools.getPodDetails("", "pod1")).contains("Error: namespace must not be empty");
        assertThat(tools.getPodDetails("default", null)).contains("Error: name must not be empty");
        assertThat(tools.getPodDetails("default", "   ")).contains("Error: name must not be empty");
        assertThat(tools.getPodDetails("a".repeat(254), "pod1")).contains("exceeds maximum Kubernetes length limit");
    }

    @Test
    void testGetPodDetailsSuccess() {
        Pod pod = new PodBuilder()
                .withNewMetadata()
                .withName("test-pod")
                .withNamespace("default")
                .endMetadata()
                .withNewSpec()
                .withNodeName("node-1")
                .endSpec()
                .withNewStatus()
                .withPhase("Running")
                .withQosClass("Guaranteed")
                .withHostIP("192.168.1.10")
                .withPodIP("10.244.0.5")
                .addNewCondition()
                .withType("Ready")
                .withStatus("True")
                .withReason("PodReady")
                .withMessage("Pod is ready")
                .endCondition()
                .addNewContainerStatus()
                .withName("main-app")
                .withReady(true)
                .withRestartCount(2)
                .withNewState()
                .withNewRunning()
                .withStartedAt("2026-10-02T10:00:00Z")
                .endRunning()
                .endState()
                .withNewLastState()
                .withNewTerminated()
                .withExitCode(137)
                .withReason("OOMKilled")
                .withMessage("Out of memory")
                .endTerminated()
                .endLastState()
                .endContainerStatus()
                .endStatus()
                .build();

        when(podResource.get()).thenReturn(pod);

        String result = tools.getPodDetails("default", "test-pod");

        assertThat(result).contains("Pod: default/test-pod");
        assertThat(result).contains("Phase: Running");
        assertThat(result).contains("Node: node-1");
        assertThat(result).contains("QoS Class: Guaranteed");
        assertThat(result).contains("Ready=True");
        assertThat(result).contains("Container: main-app (Ready: true, Restarts: 2)");
        assertThat(result).contains("Last State: Terminated (ExitCode: 137, Reason: OOMKilled");
    }

    @Test
    void testGetPodDetailsNotFound() {
        when(podResource.get()).thenReturn(null);

        String result = tools.getPodDetails("default", "missing-pod");
        assertThat(result).contains("Pod 'missing-pod' not found in namespace 'default'.");
    }

    @Test
    void testGetContainerLogsValidation() {
        assertThat(tools.getContainerLogs(null, "pod", "c", 100, false)).contains("Error: namespace must not be empty");
        assertThat(tools.getContainerLogs("default", null, "c", 100, false)).contains("Error: name must not be empty");
        assertThat(tools.getContainerLogs("default", "pod", null, 100, false)).contains("Error: containerName must not be empty");
    }

    @Test
    void testGetContainerLogsSuccess() {
        when(containerResource.getLog()).thenReturn("Sample log output\nLine 2");

        String result = tools.getContainerLogs("default", "test-pod", "app", 50, false);

        assertThat(result).contains("Logs for default/test-pod [app] (previous=false, lines=50):");
        assertThat(result).contains("Sample log output");
    }

    @Test
    void testGetContainerLogsPrevious() {
        when(terminatedLoggable.getLog()).thenReturn("Fatal error: crashed on start");

        String result = tools.getContainerLogs("default", "test-pod", "app", 100, true);

        assertThat(result).contains("Logs for default/test-pod [app] (previous=true, lines=100):");
        assertThat(result).contains("Fatal error: crashed on start");
    }

    @Test
    void testGetEventsSuccess() {
        Event event = new EventBuilder()
                .withType("Warning")
                .withReason("BackOff")
                .withMessage("Back-off restarting failed container")
                .withCount(5)
                .withLastTimestamp("2026-10-02T10:15:00Z")
                .withNewSource().withComponent("kubelet").endSource()
                .withNewInvolvedObject().withKind("Pod").withName("test-pod").endInvolvedObject()
                .build();

        EventList eventList = new EventListBuilder().withItems(event).build();
        when(namedEventOperation.withInvolvedObject(any())).thenReturn(filterWatchListDeletable);
        when(filterWatchListDeletable.list()).thenReturn(eventList);
        when(namedEventOperation.list()).thenReturn(eventList);

        String result = tools.getEvents("default", "test-pod");

        assertThat(result).contains("Recent Events in default for test-pod");
        assertThat(result).contains("[Warning] Reason: BackOff | Message: Back-off restarting failed container | Count: 5");
    }

    @Test
    void testGetDeploymentRolloutInfoDirectMatch() {
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                .withName("web-deploy")
                .withNamespace("default")
                .endMetadata()
                .withNewSpec()
                .withReplicas(3)
                .withNewStrategy().withType("RollingUpdate").endStrategy()
                .endSpec()
                .withNewStatus()
                .withReplicas(3)
                .withUpdatedReplicas(3)
                .withReadyReplicas(2)
                .withAvailableReplicas(2)
                .withUnavailableReplicas(1)
                .withObservedGeneration(2L)
                .addNewCondition()
                .withType("Available")
                .withStatus("True")
                .withReason("MinimumReplicasAvailable")
                .withMessage("Deployment has minimum availability.")
                .endCondition()
                .endStatus()
                .build();

        when(deploymentResource.get()).thenReturn(deployment);

        String result = tools.getDeploymentRolloutInfo("default", "web-deploy");

        assertThat(result).contains("Deployment: default/web-deploy");
        assertThat(result).contains("Desired Replicas: 3");
        assertThat(result).contains("Strategy: RollingUpdate");
        assertThat(result).contains("Replicas: Total=3, Updated=3, Ready=2, Available=2, Unavailable=1");
        assertThat(result).contains("Available=True (Reason: MinimumReplicasAvailable");
    }

    @Test
    void testGetDeploymentRolloutInfoNotFound() {
        when(deploymentResource.get()).thenReturn(null);
        when(podResource.get()).thenReturn(null);

        String result = tools.getDeploymentRolloutInfo("default", "missing");

        assertThat(result).contains("No Deployment found matching name or parent of 'missing' in namespace 'default'.");
    }
}
