package com.sharan.kubeon.kubernetes.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.detection.DetectedIssue;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class EvidenceCollector {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCollector.class);
    private static final int MAX_TAIL_LINES = 100;
    private static final int MAX_EVENTS = 15;

    private final KubernetesClient client;
    private final ObjectMapper objectMapper;

    public EvidenceCollector(KubernetesClient client,
                             @org.springframework.beans.factory.annotation.Autowired(required = false) ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Deterministically collects Kubernetes evidence for a detected issue.
     */
    public EvidenceBundle collect(DetectedIssue issue) {
        String ns = issue.namespace();
        String podName = issue.podName();

        log.info("Collecting evidence for issue in {}/{} (reason: {})", ns, podName, issue.reason());

        Pod pod = fetchPod(ns, podName);
        PodSpecSummary podSpec = extractPodSpec(pod);
        List<EventSummary> events = fetchRecentEvents(ns, podName);
        List<LogSnapshot> logs = fetchLogs(ns, podName, pod);

        EvidenceBundle bundle = new EvidenceBundle(
                issue,
                podSpec,
                events,
                logs,
                Instant.now()
        );

        logBundle(bundle);
        return bundle;
    }

    /**
     * Overload for on-demand or manual evidence gathering.
     */
    public EvidenceBundle collect(String namespace, String podName) {
        DetectedIssue issue = new DetectedIssue(
                namespace,
                podName,
                null,
                Instant.now(),
                "Manual evidence collection"
        );
        return collect(issue);
    }

    private Pod fetchPod(String namespace, String podName) {
        try {
            return client.pods().inNamespace(namespace).withName(podName).get();
        } catch (Exception e) {
            log.warn("Failed to retrieve pod {}/{}: {}", namespace, podName, e.getMessage());
            return null;
        }
    }

    private PodSpecSummary extractPodSpec(Pod pod) {
        if (pod == null || pod.getMetadata() == null) {
            return null;
        }

        String nodeName = pod.getSpec() != null ? pod.getSpec().getNodeName() : null;
        String restartPolicy = pod.getSpec() != null ? pod.getSpec().getRestartPolicy() : null;
        String phase = pod.getStatus() != null ? pod.getStatus().getPhase() : "Unknown";
        Map<String, String> labels = pod.getMetadata().getLabels() != null
                ? Map.copyOf(pod.getMetadata().getLabels())
                : Map.of();

        List<ContainerSummary> containerSummaries = new ArrayList<>();
        if (pod.getSpec() != null) {
            if (pod.getSpec().getInitContainers() != null) {
                for (Container c : pod.getSpec().getInitContainers()) {
                    containerSummaries.add(toContainerSummary(c, true));
                }
            }
            if (pod.getSpec().getContainers() != null) {
                for (Container c : pod.getSpec().getContainers()) {
                    containerSummaries.add(toContainerSummary(c, false));
                }
            }
        }

        return new PodSpecSummary(
                pod.getMetadata().getName(),
                pod.getMetadata().getNamespace(),
                nodeName,
                phase,
                restartPolicy,
                containerSummaries,
                labels
        );
    }

    private ContainerSummary toContainerSummary(Container c, boolean isInit) {
        Map<String, String> requests = formatQuantities(c.getResources() != null ? c.getResources().getRequests() : null);
        Map<String, String> limits = formatQuantities(c.getResources() != null ? c.getResources().getLimits() : null);

        return new ContainerSummary(
                c.getName(),
                c.getImage(),
                requests,
                limits,
                c.getLivenessProbe() != null,
                c.getReadinessProbe() != null,
                c.getStartupProbe() != null,
                isInit
        );
    }

    private Map<String, String> formatQuantities(Map<String, Quantity> quantityMap) {
        if (quantityMap == null || quantityMap.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        quantityMap.forEach((k, v) -> {
            if (v != null) {
                String amount = v.getAmount() != null ? v.getAmount() : "";
                String format = v.getFormat() != null ? v.getFormat() : "";
                result.put(k, amount + format);
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private List<EventSummary> fetchRecentEvents(String namespace, String podName) {
        try {
            var eventList = client.v1().events().inNamespace(namespace).list();
            if (eventList == null || eventList.getItems() == null) {
                return List.of();
            }

            return eventList.getItems().stream()
                    .filter(e -> e.getInvolvedObject() != null
                            && "Pod".equalsIgnoreCase(e.getInvolvedObject().getKind())
                            && podName.equalsIgnoreCase(e.getInvolvedObject().getName()))
                    .sorted(Comparator.comparing(this::extractEventTimestamp).reversed())
                    .limit(MAX_EVENTS)
                    .map(this::toEventSummary)
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to fetch events for pod {}/{}: {}", namespace, podName, e.getMessage());
            return List.of();
        }
    }

    private String extractEventTimestamp(Event e) {
        if (e.getLastTimestamp() != null && !e.getLastTimestamp().isBlank()) {
            return e.getLastTimestamp();
        }
        if (e.getEventTime() != null && e.getEventTime().getTime() != null) {
            return e.getEventTime().getTime();
        }
        if (e.getFirstTimestamp() != null && !e.getFirstTimestamp().isBlank()) {
            return e.getFirstTimestamp();
        }
        if (e.getMetadata() != null && e.getMetadata().getCreationTimestamp() != null) {
            return e.getMetadata().getCreationTimestamp();
        }
        return "";
    }

    private EventSummary toEventSummary(Event e) {
        String source = "unknown";
        if (e.getSource() != null && e.getSource().getComponent() != null) {
            source = e.getSource().getComponent();
        } else if (e.getReportingComponent() != null) {
            source = e.getReportingComponent();
        }

        return new EventSummary(
                e.getType(),
                e.getReason(),
                e.getMessage(),
                e.getCount(),
                e.getFirstTimestamp(),
                e.getLastTimestamp(),
                source
        );
    }

    private List<LogSnapshot> fetchLogs(String namespace, String podName, Pod pod) {
        if (pod == null || pod.getSpec() == null) {
            return List.of();
        }

        List<LogSnapshot> snapshots = new ArrayList<>();
        List<Container> allContainers = new ArrayList<>();
        if (pod.getSpec().getInitContainers() != null) {
            allContainers.addAll(pod.getSpec().getInitContainers());
        }
        if (pod.getSpec().getContainers() != null) {
            allContainers.addAll(pod.getSpec().getContainers());
        }

        for (Container c : allContainers) {
            snapshots.add(fetchContainerLog(namespace, podName, c.getName()));
        }

        return Collections.unmodifiableList(snapshots);
    }

    private LogSnapshot fetchContainerLog(String namespace, String podName, String containerName) {
        String logContent = null;
        boolean isPrevious = false;
        boolean truncated = false;
        String errorMessage = null;

        // 1. Try previous logs first (critical for OOMKilled and CrashLoopBackOff)
        try {
            logContent = client.pods().inNamespace(namespace).withName(podName)
                    .inContainer(containerName)
                    .terminated()
                    .tailingLines(MAX_TAIL_LINES)
                    .getLog();
            if (logContent != null && !logContent.isBlank()) {
                isPrevious = true;
            }
        } catch (Exception e) {
            log.debug("Previous logs unavailable for {}/{} (container: {}): {}",
                    namespace, podName, containerName, e.getMessage());
        }

        // 2. If previous logs unavailable, attempt current logs
        if (logContent == null || logContent.isBlank()) {
            try {
                logContent = client.pods().inNamespace(namespace).withName(podName)
                        .inContainer(containerName)
                        .tailingLines(MAX_TAIL_LINES)
                        .getLog();
            } catch (Exception e) {
                errorMessage = "Logs unavailable: " + e.getMessage();
                log.debug("Current logs unavailable for {}/{} (container: {}): {}",
                        namespace, podName, containerName, e.getMessage());
            }
        }

        if (logContent != null && !logContent.isBlank()) {
            if (logContent.lines().count() >= MAX_TAIL_LINES) {
                truncated = true;
            }
        } else if (errorMessage == null) {
            errorMessage = "No log output recorded for container";
        }

        return new LogSnapshot(containerName, isPrevious, logContent, truncated, errorMessage);
    }

    private void logBundle(EvidenceBundle bundle) {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle);
            log.info("EVIDENCE BUNDLE COLLECTED for {}/{}:\n{}",
                    bundle.issue().namespace(), bundle.issue().podName(), json);
        } catch (Exception e) {
            log.info("EVIDENCE BUNDLE COLLECTED for {}/{}: {}",
                    bundle.issue().namespace(), bundle.issue().podName(), bundle);
        }
    }
}
