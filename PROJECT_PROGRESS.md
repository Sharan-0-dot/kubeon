# Kubeon — Current Project Progress

## Last Updated

2026-09-30

## Current Phase

Phase 2 — Detection: the Watch mechanism (Completed) → Transitioning to Phase 3 — Evidence gathering

## Current Objective

Complete Phase 2 checkpoint commit, then begin Phase 3 by defining `EvidenceBundle` and building `EvidenceCollector` to fetch pod spec, recent events, previous logs, and resource limits upon issue detection.

## Overall Progress

- [x] Phase 0: Project foundation (Spring Boot 4.1.1, Java 21, WebMvc, Actuator, Lombok)
- [x] Phase 1: Cluster connectivity via Fabric8 (6.13.4) and local kubeconfig (~/.kube/config)
- [x] Phase 1: List pods across all namespaces via `ClusterConnectivityCheck` CommandLineRunner verified against Minikube
- [x] Phase 2: `BadStateReason` enum expanded (`OOM_KILLED`, `CRASH_LOOP_BACKOFF`, `IMAGE_PULL_BACK_OFF`, `ERR_IMAGE_PULL`, `UNHEALTHY_PROBE`, `FAILED_SCHEDULING`)
- [x] Phase 2: `PodWatcher` with streaming `client.pods().inAnyNamespace().watch()` checking container & init-container waiting/terminated statuses
- [x] Phase 2: `EventWatcher` with streaming `client.v1().events().inAnyNamespace().watch()` accurately classifying probe failures and backoffs
- [x] Phase 2: Thread-safe `IssueDeduplicator` preventing duplicate alerts across both watchers
- [x] Phase 2: Live end-to-end verification against Minikube failure scenarios (`oom-test`, probe failures, bad image pulls)
- [ ] Phase 3: Evidence collection pipeline (`EvidenceCollector`, `EvidenceBundle`)
- [ ] Phase 4: LLM reasoning layer (Ollama / local LLM, `Diagnosis`, `ReasoningAgent`)
- [ ] Phase 5: Agentic tool calling for follow-up evidence
- [ ] Phase 6: Incident store, REST API (`/api/health`, `/api/incidents`, `/api/investigate`), Slack notifications
- [ ] Phase 7: Sensitive-data redaction filter
- [ ] Phase 8: Containerization and in-cluster deployment (Dockerfile, RBAC, Deployment)
- [ ] Phase 9: Packaging & open-source polish

## What Has Been Implemented

1. **Kubernetes Client Integration**: `KubernetesClientConfig` initializes Fabric8 `KubernetesClient` auto-configuring from local kubeconfig.
2. **Connectivity Smoke Test**: `ClusterConnectivityCheck` lists all pods on startup to confirm cluster access.
3. **Pod Anomaly Detection**: `PodWatcher` streams pod lifecycle events across all namespaces, inspecting standard and init container statuses (`waiting` and `terminated`).
4. **Cluster Event Anomaly Detection**: `EventWatcher` streams Kubernetes core `Event` objects, classifying probe failures (`UNHEALTHY_PROBE`), scheduling issues (`FAILED_SCHEDULING`), backoffs, and pull errors.
5. **Issue Deduplication**: `IssueDeduplicator` provides thread-safe `Set`-backed key deduplication (`namespace/name/reason`) and cleans up on pod deletion.
6. **Automated Unit Testing**: Comprehensive unit tests covering `BadStateReasonTest` and `IssueDeduplicatorTest`.

## Current Implementation

- `src/main/java/com/sharan/kubeon/KubernetesClientConfig.java`: Spring `@Configuration` defining `KubernetesClient` bean using `KubernetesClientBuilder`.
- `src/main/java/com/sharan/kubeon/ClusterConnectivityCheck.java`: `CommandLineRunner` listing pods cluster-wide on startup.
- `src/main/java/com/sharan/kubeon/BadStateReason.java`: Enum matching Kubernetes container status reasons and Event reasons.
- `src/main/java/com/sharan/kubeon/DetectedIssue.java`: Immutable record holding namespace, podName, reason, timestamp, and source message.
- `src/main/java/com/sharan/kubeon/IssueDeduplicator.java`: Shared deduplicator across watchers to prevent duplicate alerts.
- `src/main/java/com/sharan/kubeon/PodWatcher.java`: Streaming watch on Kubernetes Pods.
- `src/main/java/com/sharan/kubeon/EventWatcher.java`: Streaming watch on Kubernetes Events.
- `src/test/java/com/sharan/kubeon/BadStateReasonTest.java`: Unit tests for reason mapping and case insensitivity.
- `src/test/java/com/sharan/kubeon/IssueDeduplicatorTest.java`: Unit tests for thread-safe deduplication and deletion cleanup.
- `demo-test-files/oom-test.yaml`: Test pod specification running `polinux/stress` with memory limit.

## Verified Working

- All 7 tests pass clean (`.\mvnw.cmd test`).
- Fabric8 client connects to local Minikube cluster (v1.34.0, node `minikube`).
- Live Minikube detection tested and verified:
  - `PodWatcher` caught `oom-test` `CRASH_LOOP_BACKOFF` and `OOM_KILLED`.
  - `EventWatcher` caught `coredns` `UNHEALTHY_PROBE` (`Readiness probe failed`).
  - `EventWatcher` caught `bad-image` `IMAGE_PULL_BACK_OFF` and `ERR_IMAGE_PULL`.
- Clean pod deletion clears tracked keys from `IssueDeduplicator`.

## Minikube Verification

- `kubectl get nodes`: node `minikube` is `Ready`, control-plane, v1.34.0.
- `kubectl get pods -A`: system pods active.
- `kubectl apply -f demo-test-files/oom-test.yaml`: successfully reproduced OOMKilled and verified detection in logs.
- `kubectl delete pod oom-test`: cleaned up successfully.

## Current Problem / Blocker

None. Phase 2 goals have been satisfied and verified.

## Last Completed Step

Implemented `EventWatcher`, updated `BadStateReason` with probe and scheduling reasons, added `IssueDeduplicator`, and created unit tests. Verified live detection against Minikube.

## Next Step

Commit Phase 2 work with message "Add pod/event watch with rule-based bad-state detection", then proceed to Phase 3 (Evidence gathering: `EvidenceBundle` and `EvidenceCollector`).

## Important Technical Decisions

- Using Fabric8 Kubernetes Client 6.13.4.
- Read-only operations only (`list`, `get`, `watch`); no mutating cluster actions.
- Event-driven streaming via Kubernetes Watch API for both Pods and Events.
- Shared `IssueDeduplicator` ensures single alert per `(namespace, pod, reason)` across both watch sources.
- In `EventWatcher`, context-aware parsing separates `BackOff` into `IMAGE_PULL_BACK_OFF` vs `CRASH_LOOP_BACKOFF` based on event message content.

## Recently Changed Files

- `src/main/java/com/sharan/kubeon/BadStateReason.java`
- `src/main/java/com/sharan/kubeon/PodWatcher.java`
- `src/main/java/com/sharan/kubeon/EventWatcher.java`
- `src/main/java/com/sharan/kubeon/IssueDeduplicator.java`
- `src/test/java/com/sharan/kubeon/BadStateReasonTest.java`
- `src/test/java/com/sharan/kubeon/IssueDeduplicatorTest.java`
- `PROJECT_PROGRESS.md`

## Tests / Commands

- `.\mvnw.cmd test`: All 7 tests passing.
- `kubectl get events -A`: Verified event correlation with `EventWatcher`.

## Git State

- Branch: `main`
- Latest commit: `af34b86 Checked whether the events are watched properly by fabric8 and failures are detected`
- Uncommitted changes: `PROJECT_PROGRESS.md`, `BadStateReason.java`, `PodWatcher.java`, `EventWatcher.java`, `IssueDeduplicator.java`, `BadStateReasonTest.java`, `IssueDeduplicatorTest.java`

## Session Notes

- Completed Phase 2 implementation and verification.
- Ready for Git commit and transition to Phase 3.
