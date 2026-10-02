package com.sharan.kubeon.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class KubernetesEvidenceTools {

    private static final Logger log = LoggerFactory.getLogger(KubernetesEvidenceTools.class);
    private static final int DEFAULT_TAIL_LINES = 100;
    private static final int MAX_TAIL_LINES = 500;
    private static final int MAX_EVENTS = 15;

    private final KubernetesClient client;

    public KubernetesEvidenceTools(KubernetesClient client) {
        this.client = client;
    }

    @Tool("Get detailed status and conditions for a specific pod, including container states, restart counts, exit codes, and node assignment")
    public String getPodDetails(
            @P("The Kubernetes namespace of the pod (e.g. 'default')") String namespace,
            @P("The name of the pod") String podName) {

        String validation = validateNamespaceAndName(namespace, podName);
        if (validation != null) {
            return validation;
        }

        try {
            Pod pod = client.pods().inNamespace(namespace.trim()).withName(podName.trim()).get();
            if (pod == null) {
                return String.format("Pod '%s' not found in namespace '%s'.", podName, namespace);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Pod: %s/%s\n", namespace, podName));
            if (pod.getStatus() != null) {
                sb.append(String.format("Phase: %s\n", pod.getStatus().getPhase()));
                sb.append(String.format("Node: %s\n", pod.getSpec() != null ? pod.getSpec().getNodeName() : "unknown"));
                sb.append(String.format("QoS Class: %s\n", pod.getStatus().getQosClass()));
                sb.append(String.format("Host IP: %s, Pod IP: %s\n", pod.getStatus().getHostIP(), pod.getStatus().getPodIP()));

                if (pod.getStatus().getConditions() != null && !pod.getStatus().getConditions().isEmpty()) {
                    sb.append("Conditions:\n");
                    for (PodCondition c : pod.getStatus().getConditions()) {
                        sb.append(String.format("  - %s=%s (Reason: %s, Message: %s)\n",
                                c.getType(), c.getStatus(), c.getReason(), c.getMessage()));
                    }
                }

                appendContainerStatuses(sb, "Container Statuses", pod.getStatus().getContainerStatuses());
                appendContainerStatuses(sb, "Init Container Statuses", pod.getStatus().getInitContainerStatuses());
            }
            return sb.toString();
        } catch (KubernetesClientException e) {
            log.warn("Kubernetes API error fetching pod details for {}/{}: {}", namespace, podName, e.getMessage());
            return String.format("Error fetching pod details for %s/%s: %s", namespace, podName, e.getMessage());
        }
    }

    @Tool("Get recent container log lines, with optional previous-instance log retrieval for crashed containers")
    public String getContainerLogs(
            @P("The Kubernetes namespace") String namespace,
            @P("The name of the pod") String podName,
            @P("The name of the container") String containerName,
            @P("Number of lines from end of log to retrieve (1 to 500, default 100)") Integer tailLines,
            @P("Set true to fetch logs from the previously terminated container instance") Boolean previous) {

        String validation = validateNamespaceAndName(namespace, podName);
        if (validation != null) {
            return validation;
        }
        if (containerName == null || containerName.isBlank()) {
            return "Error: containerName must not be empty.";
        }

        int lines = (tailLines == null || tailLines <= 0) ? DEFAULT_TAIL_LINES : Math.min(tailLines, MAX_TAIL_LINES);
        boolean isPrevious = Boolean.TRUE.equals(previous);

        try {
            String logContent;
            if (isPrevious) {
                try {
                    logContent = client.pods()
                            .inNamespace(namespace.trim())
                            .withName(podName.trim())
                            .inContainer(containerName.trim())
                            .terminated()
                            .tailingLines(lines)
                            .getLog();
                } catch (KubernetesClientException e) {
                    logContent = client.pods()
                            .inNamespace(namespace.trim())
                            .withName(podName.trim())
                            .inContainer(containerName.trim())
                            .tailingLines(lines)
                            .getLog();
                }
            } else {
                logContent = client.pods()
                        .inNamespace(namespace.trim())
                        .withName(podName.trim())
                        .inContainer(containerName.trim())
                        .tailingLines(lines)
                        .getLog();
            }

            if (logContent == null || logContent.isBlank()) {
                return String.format("No log output returned for container '%s' in pod '%s/%s' (previous=%s).",
                        containerName, namespace, podName, isPrevious);
            }

            return String.format("Logs for %s/%s [%s] (previous=%s, lines=%d):\n%s",
                    namespace, podName, containerName, isPrevious, lines, logContent);
        } catch (KubernetesClientException e) {
            log.warn("Error fetching logs for {}/{}/{}: {}", namespace, podName, containerName, e.getMessage());
            return String.format("Error retrieving logs for container '%s' in pod '%s/%s': %s",
                    containerName, namespace, podName, e.getMessage());
        }
    }

    @Tool("Get recent Kubernetes events associated with a pod or namespace to discover probe failures, scheduling errors, and lifecycle events")
    public String getEvents(
            @P("The Kubernetes namespace") String namespace,
            @P("Optional pod name to filter events specifically for that pod") String podName) {

        if (namespace == null || namespace.isBlank()) {
            return "Error: namespace must not be empty.";
        }

        try {
            List<Event> events;
            if (podName != null && !podName.isBlank()) {
                events = client.v1().events()
                        .inNamespace(namespace.trim())
                        .withInvolvedObject(new ObjectReferenceBuilder().withKind("Pod").withName(podName.trim()).build())
                        .list()
                        .getItems();
                if (events == null || events.isEmpty()) {
                    events = client.v1().events()
                            .inNamespace(namespace.trim())
                            .list()
                            .getItems()
                            .stream()
                            .filter(e -> e.getInvolvedObject() != null && podName.trim().equals(e.getInvolvedObject().getName()))
                            .collect(Collectors.toList());
                }
            } else {
                events = client.v1().events()
                        .inNamespace(namespace.trim())
                        .list()
                        .getItems();
            }

            if (events == null || events.isEmpty()) {
                return String.format("No events found in namespace '%s'%s.",
                        namespace, (podName != null ? " for pod '" + podName + "'" : ""));
            }

            List<Event> sorted = events.stream()
                    .sorted((e1, e2) -> {
                        String t1 = e1.getLastTimestamp() != null ? e1.getLastTimestamp() : (e1.getEventTime() != null ? e1.getEventTime().getTime() : "");
                        String t2 = e2.getLastTimestamp() != null ? e2.getLastTimestamp() : (e2.getEventTime() != null ? e2.getEventTime().getTime() : "");
                        return t2.compareTo(t1);
                    })
                    .limit(MAX_EVENTS)
                    .toList();

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Recent Events in %s%s (showing %d):\n",
                    namespace, (podName != null ? " for " + podName : ""), sorted.size()));

            for (Event e : sorted) {
                sb.append(String.format("  - [%s] Reason: %s | Message: %s | Count: %s | Source: %s | LastSeen: %s\n",
                        e.getType() != null ? e.getType() : "Normal",
                        e.getReason(),
                        e.getMessage(),
                        e.getCount() != null ? e.getCount() : 1,
                        e.getSource() != null ? e.getSource().getComponent() : "unknown",
                        e.getLastTimestamp() != null ? e.getLastTimestamp() : "unknown"));
            }

            return sb.toString();
        } catch (KubernetesClientException e) {
            log.warn("Error fetching events in namespace {}: {}", namespace, e.getMessage());
            return String.format("Error fetching events in namespace '%s': %s", namespace, e.getMessage());
        }
    }

    @Tool("Get rollout status, replica counts, conditions, and update history for a Deployment")
    public String getDeploymentRolloutInfo(
            @P("The Kubernetes namespace") String namespace,
            @P("The name of the Deployment (or pod name to locate parent deployment)") String name) {

        String validation = validateNamespaceAndName(namespace, name);
        if (validation != null) {
            return validation;
        }

        try {
            Deployment deployment = client.apps().deployments().inNamespace(namespace.trim()).withName(name.trim()).get();

            if (deployment == null) {
                Pod pod = client.pods().inNamespace(namespace.trim()).withName(name.trim()).get();
                if (pod != null && pod.getMetadata() != null && pod.getMetadata().getOwnerReferences() != null) {
                    for (OwnerReference ref : pod.getMetadata().getOwnerReferences()) {
                        if ("ReplicaSet".equalsIgnoreCase(ref.getKind())) {
                            var rs = client.apps().replicaSets().inNamespace(namespace.trim()).withName(ref.getName()).get();
                            if (rs != null && rs.getMetadata() != null && rs.getMetadata().getOwnerReferences() != null) {
                                for (OwnerReference rsRef : rs.getMetadata().getOwnerReferences()) {
                                    if ("Deployment".equalsIgnoreCase(rsRef.getKind())) {
                                        deployment = client.apps().deployments().inNamespace(namespace.trim()).withName(rsRef.getName()).get();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (deployment == null) {
                return String.format("No Deployment found matching name or parent of '%s' in namespace '%s'.", name, namespace);
            }

            StringBuilder sb = new StringBuilder();
            String depName = deployment.getMetadata().getName();
            sb.append(String.format("Deployment: %s/%s\n", namespace, depName));
            if (deployment.getSpec() != null) {
                sb.append(String.format("Desired Replicas: %d\n", deployment.getSpec().getReplicas() != null ? deployment.getSpec().getReplicas() : 1));
                if (deployment.getSpec().getStrategy() != null) {
                    sb.append(String.format("Strategy: %s\n", deployment.getSpec().getStrategy().getType()));
                }
            }

            DeploymentStatus status = deployment.getStatus();
            if (status != null) {
                sb.append(String.format("Replicas: Total=%s, Updated=%s, Ready=%s, Available=%s, Unavailable=%s\n",
                        status.getReplicas(), status.getUpdatedReplicas(), status.getReadyReplicas(),
                        status.getAvailableReplicas(), status.getUnavailableReplicas()));
                sb.append(String.format("Observed Generation: %s\n", status.getObservedGeneration()));

                if (status.getConditions() != null && !status.getConditions().isEmpty()) {
                    sb.append("Deployment Conditions:\n");
                    for (DeploymentCondition dc : status.getConditions()) {
                        sb.append(String.format("  - %s=%s (Reason: %s, Message: %s)\n",
                                dc.getType(), dc.getStatus(), dc.getReason(), dc.getMessage()));
                    }
                }
            }

            return sb.toString();
        } catch (KubernetesClientException e) {
            log.warn("Error fetching deployment rollout info for {}/{}: {}", namespace, name, e.getMessage());
            return String.format("Error fetching deployment rollout info for %s/%s: %s", namespace, name, e.getMessage());
        }
    }

    private void appendContainerStatuses(StringBuilder sb, String title, List<ContainerStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) return;
        sb.append(title).append(":\n");
        for (ContainerStatus cs : statuses) {
            sb.append(String.format("  - Container: %s (Ready: %s, Restarts: %d)\n",
                    cs.getName(), cs.getReady(), cs.getRestartCount() != null ? cs.getRestartCount() : 0));

            ContainerState state = cs.getState();
            if (state != null) {
                if (state.getRunning() != null) {
                    sb.append(String.format("    State: Running (StartedAt: %s)\n", state.getRunning().getStartedAt()));
                } else if (state.getWaiting() != null) {
                    sb.append(String.format("    State: Waiting (Reason: %s, Message: %s)\n",
                            state.getWaiting().getReason(), state.getWaiting().getMessage()));
                } else if (state.getTerminated() != null) {
                    sb.append(String.format("    State: Terminated (ExitCode: %d, Reason: %s, Message: %s)\n",
                            state.getTerminated().getExitCode(), state.getTerminated().getReason(), state.getTerminated().getMessage()));
                }
            }

            ContainerState lastState = cs.getLastState();
            if (lastState != null && lastState.getTerminated() != null) {
                sb.append(String.format("    Last State: Terminated (ExitCode: %d, Reason: %s, Message: %s)\n",
                        lastState.getTerminated().getExitCode(), lastState.getTerminated().getReason(), lastState.getTerminated().getMessage()));
            }
        }
    }

    private String validateNamespaceAndName(String namespace, String name) {
        if (namespace == null || namespace.isBlank()) {
            return "Error: namespace must not be empty.";
        }
        if (name == null || name.isBlank()) {
            return "Error: name must not be empty.";
        }
        if (namespace.length() > 253 || name.length() > 253) {
            return "Error: namespace or name exceeds maximum Kubernetes length limit (253 characters).";
        }
        return null;
    }
}
