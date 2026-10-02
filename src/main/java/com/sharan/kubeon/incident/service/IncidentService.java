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
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository repository;
    private final EvidenceCollector evidenceCollector;
    private final ReasoningAgent reasoningAgent;
    private final SlackNotifier slackNotifier;
    private final KubernetesClient kubernetesClient;
    private final PodWatcher podWatcher;

    public IncidentService(IncidentRepository repository,
                           EvidenceCollector evidenceCollector,
                           ReasoningAgent reasoningAgent,
                           SlackNotifier slackNotifier,
                           KubernetesClient kubernetesClient,
                           PodWatcher podWatcher) {
        this.repository = repository;
        this.evidenceCollector = evidenceCollector;
        this.reasoningAgent = reasoningAgent;
        this.slackNotifier = slackNotifier;
        this.kubernetesClient = kubernetesClient;
        this.podWatcher = podWatcher;
    }

    public Incident processDetectedIssue(DetectedIssue issue) {
        log.info("Processing detected issue for {}/{} (reason: {})",
                issue.namespace(), issue.podName(), issue.reason());

        Incident incident = Incident.create(issue, TriggerType.AUTO_DETECTED);
        incident = repository.save(incident);

        return executeInvestigation(incident, issue);
    }

    public Incident investigate(String namespace, String podName) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Namespace must not be empty");
        }
        if (podName == null || podName.isBlank()) {
            throw new IllegalArgumentException("Pod name must not be empty");
        }

        log.info("Manual investigation requested for {}/{}", namespace, podName);

        Pod pod = kubernetesClient.pods().inNamespace(namespace.trim()).withName(podName.trim()).get();
        if (pod == null) {
            throw new ResourceNotFoundException(
                    String.format("Pod '%s' not found in namespace '%s'", podName, namespace));
        }

        List<DetectedIssue> issues = podWatcher.detect(pod);
        BadStateReason reason = issues.isEmpty() ? null : issues.get(0).reason();
        String message = issues.isEmpty() ? "Manual on-demand investigation" : issues.get(0).sourceMessage();

        DetectedIssue issue = new DetectedIssue(
                namespace.trim(),
                podName.trim(),
                reason,
                Instant.now(),
                message
        );

        Incident incident = Incident.create(issue, TriggerType.MANUAL);
        incident = repository.save(incident);

        return executeInvestigation(incident, issue);
    }

    private Incident executeInvestigation(Incident incident, DetectedIssue issue) {
        try {
            // 1. Evidence gathering
            incident = incident.withStatus(IncidentStatus.INVESTIGATING);
            EvidenceBundle bundle = evidenceCollector.collect(issue);
            incident = incident.withEvidence(bundle, IncidentStatus.INVESTIGATING);
            incident = repository.save(incident);

            // 2. LLM Reasoning
            Diagnosis diagnosis = reasoningAgent.diagnose(bundle);
            incident = incident.withDiagnosis(diagnosis, IncidentStatus.DIAGNOSED);
            incident = repository.save(incident);

            // 3. Notification
            boolean notified = slackNotifier.notify(incident);
            incident = incident.withNotified(notified);
            return repository.save(incident);

        } catch (Exception e) {
            log.error("Error during incident investigation for {}/{}: {}",
                    incident.namespace(), incident.podName(), e.getMessage(), e);

            Diagnosis fallbackDiagnosis = new Diagnosis(
                    "Investigation failed: " + e.getMessage(),
                    ConfidenceLevel.LOW,
                    "Check cluster status and agent logs.",
                    List.of(),
                    "unknown"
            );
            incident = incident.withDiagnosis(fallbackDiagnosis, IncidentStatus.INVESTIGATING);
            return repository.save(incident);
        }
    }

    public Optional<Incident> getIncident(UUID id) {
        return repository.findById(id);
    }

    public List<Incident> listIncidents(String namespace) {
        if (namespace != null && !namespace.isBlank()) {
            return repository.findByNamespace(namespace.trim());
        }
        return repository.findAll();
    }

    public HealthCheckResult runHealthCheck() {
        try {
            List<Pod> allPods = kubernetesClient.pods().inAnyNamespace().list().getItems();
            List<DetectedIssue> detectedIssues = new ArrayList<>();

            for (Pod pod : allPods) {
                detectedIssues.addAll(podWatcher.detect(pod));
            }

            String status = detectedIssues.isEmpty() ? "OK" : "ISSUES_FOUND";
            return new HealthCheckResult(status, allPods.size(), detectedIssues.size(), detectedIssues);
        } catch (Exception e) {
            log.error("Error running health check across cluster: {}", e.getMessage(), e);
            return new HealthCheckResult("ERROR: " + e.getMessage(), 0, 0, List.of());
        }
    }
}
