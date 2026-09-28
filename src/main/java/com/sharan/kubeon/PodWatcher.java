package com.sharan.kubeon;

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
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PodWatcher {

    private static final Logger log = LoggerFactory.getLogger(PodWatcher.class);

    private final KubernetesClient client;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private Watch watch;

    public PodWatcher(KubernetesClient client) {
        this.client = client;
    }

    @PostConstruct
    public void start() {
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
        String ns = pod.getMetadata().getNamespace();
        String name = pod.getMetadata().getName();

        if (action == Watcher.Action.DELETED) {
            reported.removeIf(k -> k.startsWith(ns + "/" + name + "/"));
            return;
        }

        detect(pod).forEach(issue -> {
            String key = ns + "/" + name + "/" + issue.reason();
            if (reported.add(key)) {
                log.warn("DETECTED: {}", issue);
            }
        });
    }

    private List<DetectedIssue> detect(Pod pod) {
        List<DetectedIssue> issues = new ArrayList<>();
        var statuses = pod.getStatus() == null ? null : pod.getStatus().getContainerStatuses();
        if (statuses == null) return issues;

        for (ContainerStatus cs : statuses) {
            if (cs.getState() != null && cs.getState().getWaiting() != null) {
                var w = cs.getState().getWaiting();
                BadStateReason.fromK8sReason(w.getReason())
                        .ifPresent(r -> issues.add(build(pod, r, w.getMessage())));
            }
            if (cs.getLastState() != null && cs.getLastState().getTerminated() != null) {
                var t = cs.getLastState().getTerminated();
                BadStateReason.fromK8sReason(t.getReason())
                        .ifPresent(r -> issues.add(build(pod, r, t.getMessage())));
            }
        }
        return issues;
    }

    private DetectedIssue build(Pod pod, BadStateReason reason, String message) {
        return new DetectedIssue(
                pod.getMetadata().getNamespace(),
                pod.getMetadata().getName(),
                reason, Instant.now(), message);
    }
}