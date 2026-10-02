package com.sharan.kubeon.kubernetes.watcher;

import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.detection.IssueDeduplicator;
import com.sharan.kubeon.incident.service.IncidentService;
import io.fabric8.kubernetes.api.model.Event;
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

@Component
public class EventWatcher {

    private static final Logger log = LoggerFactory.getLogger(EventWatcher.class);

    private final KubernetesClient client;
    private final IssueDeduplicator deduplicator;
    private final IncidentService incidentService;
    private Watch watch;

    @Value("${kubeon.watcher.enabled:true}")
    private boolean watcherEnabled;

    public EventWatcher(KubernetesClient client,
                        IssueDeduplicator deduplicator,
                        @Lazy IncidentService incidentService) {
        this.client = client;
        this.deduplicator = deduplicator;
        this.incidentService = incidentService;
    }

    @PostConstruct
    public void start() {
        if (!watcherEnabled) {
            log.info("EventWatcher background watch is disabled by configuration");
            return;
        }
        watch = client.v1().events().inAnyNamespace().watch(new Watcher<Event>() {
            @Override
            public void eventReceived(Action action, Event event) {
                handle(action, event);
            }

            @Override
            public void onClose(WatcherException e) {
                log.error("Event watch closed", e);
            }
        });
        log.info("EventWatcher started");
    }

    @PreDestroy
    public void stop() {
        if (watch != null) {
            watch.close();
        }
    }

    private void handle(Watcher.Action action, Event event) {
        if (event == null || event.getInvolvedObject() == null) {
            return;
        }

        if (!"Pod".equalsIgnoreCase(event.getInvolvedObject().getKind())) {
            return;
        }

        String reason = event.getReason();
        String message = event.getMessage() != null ? event.getMessage() : "";

        BadStateReason badReason = null;
        if ("BackOff".equalsIgnoreCase(reason)) {
            if (message.toLowerCase().contains("pulling image")) {
                badReason = BadStateReason.IMAGE_PULL_BACK_OFF;
            } else {
                badReason = BadStateReason.CRASH_LOOP_BACKOFF;
            }
        } else if ("Failed".equalsIgnoreCase(reason) && message.toLowerCase().contains("image")) {
            badReason = BadStateReason.ERR_IMAGE_PULL;
        } else {
            badReason = BadStateReason.fromK8sReason(reason).orElse(null);
        }

        if (badReason != null) {
            String ns = event.getInvolvedObject().getNamespace();
            String name = event.getInvolvedObject().getName();

            if (deduplicator.isNew(ns, name, badReason)) {
                DetectedIssue issue = new DetectedIssue(
                        ns,
                        name,
                        badReason,
                        Instant.now(),
                        event.getMessage()
                );
                log.warn("DETECTED (Event): {}", issue);
                try {
                    incidentService.processDetectedIssue(issue);
                } catch (Exception e) {
                    log.error("Error processing incident for event in {}/{}: {}", ns, name, e.getMessage());
                }
            }
        }
    }
}
