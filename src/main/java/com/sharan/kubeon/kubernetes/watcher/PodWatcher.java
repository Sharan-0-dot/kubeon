package com.sharan.kubeon.kubernetes.watcher;

import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.detection.IssueDeduplicator;
import com.sharan.kubeon.incident.service.IncidentService;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class PodWatcher {

    private static final Logger log = LoggerFactory.getLogger(PodWatcher.class);

    private final KubernetesClient client;
    private final IssueDeduplicator deduplicator;
    private final IncidentService incidentService;
    private Watch watch;

    @Value("${kubeon.watcher.enabled:true}")
    private boolean watcherEnabled;

    public PodWatcher(KubernetesClient client,
                      IssueDeduplicator deduplicator,
                      @Lazy IncidentService incidentService) {
        this.client = client;
        this.deduplicator = deduplicator;
        this.incidentService = incidentService;
    }

    @PostConstruct
    public void start() {
        if (!watcherEnabled) {
            log.info("PodWatcher background watch is disabled by configuration");
            return;
        }
        watch = client.pods().inAnyNamespace().watch(new Watcher<Pod>() {
            @Override
            public void eventReceived(Action action, Pod pod) {
                handle(action, pod);
            }

            @Override
            public void onClose(WatcherException e) {
                log.error("Pod watch closed", e);
            }
        });
        log.info("PodWatcher started");
    }

    @PreDestroy
    public void stop() {
        if (watch != null) watch.close();
    }

    private void handle(Watcher.Action action, Pod pod) {
        if (pod == null || pod.getMetadata() == null) return;

        String ns = pod.getMetadata().getNamespace();
        String name = pod.getMetadata().getName();

        if (action == Watcher.Action.DELETED) {
            deduplicator.clear(ns, name);
            return;
        }

        detect(pod).forEach(issue -> {
            if (deduplicator.isNew(ns, name, issue.reason())) {
                log.warn("DETECTED (Pod): {}", issue);
                try {
                    incidentService.processDetectedIssue(issue);
                } catch (Exception e) {
                    log.error("Error processing incident for {}/{}: {}", ns, name, e.getMessage());
                }
            }
        });
    }

    public List<DetectedIssue> detect(Pod pod) {
        List<DetectedIssue> issues = new ArrayList<>();
        if (pod == null || pod.getStatus() == null) return issues;

        inspectStatuses(pod, pod.getStatus().getContainerStatuses(), issues);
        inspectStatuses(pod, pod.getStatus().getInitContainerStatuses(), issues);

        return issues;
    }

    private void inspectStatuses(Pod pod, List<ContainerStatus> statuses, List<DetectedIssue> issues) {
        if (statuses == null) return;

        for (ContainerStatus cs : statuses) {
            if (cs.getState() != null) {
                if (cs.getState().getWaiting() != null) {
                    var w = cs.getState().getWaiting();
                    BadStateReason.fromK8sReason(w.getReason())
                            .ifPresent(r -> issues.add(build(pod, r, w.getMessage())));
                }
                if (cs.getState().getTerminated() != null) {
                    var t = cs.getState().getTerminated();
                    BadStateReason.fromK8sReason(t.getReason())
                            .ifPresent(r -> issues.add(build(pod, r, t.getMessage())));
                }
            }
            if (cs.getLastState() != null && cs.getLastState().getTerminated() != null) {
                var t = cs.getLastState().getTerminated();
                BadStateReason.fromK8sReason(t.getReason())
                        .ifPresent(r -> issues.add(build(pod, r, t.getMessage())));
            }
        }
    }

    private DetectedIssue build(Pod pod, BadStateReason reason, String message) {
        return new DetectedIssue(
                pod.getMetadata().getNamespace(),
                pod.getMetadata().getName(),
                reason, Instant.now(), message);
    }
}