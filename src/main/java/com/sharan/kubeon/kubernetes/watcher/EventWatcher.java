package com.sharan.kubeon.kubernetes.watcher;

import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.detection.IssueDeduplicator;
import io.fabric8.kubernetes.api.model.Event;
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

import com.sharan.kubeon.kubernetes.evidence.EvidenceBundle;
import com.sharan.kubeon.kubernetes.evidence.EvidenceCollector;
import com.sharan.kubeon.reasoning.ReasoningAgent;

@Component
public class EventWatcher {

    private static final Logger log = LoggerFactory.getLogger(EventWatcher.class);

    private final KubernetesClient client;
    private final IssueDeduplicator deduplicator;
    private final EvidenceCollector evidenceCollector;
    private final ReasoningAgent reasoningAgent;
    private Watch watch;

    public EventWatcher(KubernetesClient client,
                        IssueDeduplicator deduplicator,
                        EvidenceCollector evidenceCollector,
                        ReasoningAgent reasoningAgent) {
        this.client = client;
        this.deduplicator = deduplicator;
        this.evidenceCollector = evidenceCollector;
        this.reasoningAgent = reasoningAgent;
    }

    @PostConstruct
    public void start() {
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
                    EvidenceBundle bundle = evidenceCollector.collect(issue);
                    reasoningAgent.diagnose(bundle);
                } catch (Exception e) {
                    log.error("Error diagnosing event issue in {}/{}: {}", ns, name, e.getMessage());
                }
            }
        }
    }
}
