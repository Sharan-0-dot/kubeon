# Kubeon — Current Project Progress

## Last Updated

2026-10-01

## Current Phase

Package Organization Refactor (Completed) → Transitioning to Phase 3 — Evidence gathering

## Current Objective

Commit the package organization refactor, then begin Phase 3 by defining `EvidenceBundle` and building `EvidenceCollector` under `com.sharan.kubeon.kubernetes.evidence`.

## Overall Progress

- [x] Phase 0: Project foundation (Spring Boot 4.1.1, Java 21, WebMvc, Actuator, Lombok)
- [x] Phase 1: Cluster connectivity via Fabric8 (6.13.4) and local kubeconfig (~/.kube/config)
- [x] Phase 1: List pods across all namespaces via `ClusterConnectivityCheck` CommandLineRunner verified against Minikube
- [x] Phase 2: `BadStateReason` enum expanded (`OOM_KILLED`, `CRASH_LOOP_BACKOFF`, `IMAGE_PULL_BACK_OFF`, `ERR_IMAGE_PULL`, `UNHEALTHY_PROBE`, `FAILED_SCHEDULING`)
- [x] Phase 2: `PodWatcher` with streaming `client.pods().inAnyNamespace().watch()` checking container & init-container waiting/terminated statuses
- [x] Phase 2: `EventWatcher` with streaming `client.v1().events().inAnyNamespace().watch()` accurately classifying probe failures and backoffs
- [x] Phase 2: Thread-safe `IssueDeduplicator` preventing duplicate alerts across both watchers
- [x] Phase 2: Live end-to-end verification against Minikube failure scenarios (`oom-test`, probe failures, bad image pulls)
- [x] Package Refactor: Reorganized flat project package into modular structure (`config`, `kubernetes.watcher`, `detection`) mirroring in `src/test`
- [ ] Phase 3: Evidence collection pipeline (`EvidenceCollector`, `EvidenceBundle` under `kubernetes.evidence`)
- [ ] Phase 4: LLM reasoning layer (Ollama / local LLM, `Diagnosis`, `ReasoningAgent`)
- [ ] Phase 5: Agentic tool calling for follow-up evidence
- [ ] Phase 6: Incident store, REST API (`/api/health`, `/api/incidents`, `/api/investigate`), Slack notifications
- [ ] Phase 7: Sensitive-data redaction filter
- [ ] Phase 8: Containerization and in-cluster deployment (Dockerfile, RBAC, Deployment)
- [ ] Phase 9: Packaging & open-source polish

## What Has Been Implemented

1. **Package Organization Refactor**:
   - Reorganized flat `com.sharan.kubeon` package into dedicated subpackages:
     - `config`: `KubernetesClientConfig`
     - `kubernetes`: `ClusterConnectivityCheck`
     - `kubernetes.watcher`: `PodWatcher`, `EventWatcher`
     - `detection`: `BadStateReason`, `DetectedIssue`, `IssueDeduplicator`
   - Mirrored package hierarchy in tests (`com.sharan.kubeon.detection`).
2. **Kubernetes Client Integration**: `KubernetesClientConfig` initializes Fabric8 `KubernetesClient` auto-configuring from local kubeconfig.
3. **Connectivity Smoke Test**: `ClusterConnectivityCheck` lists all pods on startup to confirm cluster access.
4. **Pod Anomaly Detection**: `PodWatcher` streams pod lifecycle events across all namespaces, inspecting standard and init container statuses (`waiting` and `terminated`).
5. **Cluster Event Anomaly Detection**: `EventWatcher` streams Kubernetes core `Event` objects, classifying probe failures (`UNHEALTHY_PROBE`), scheduling issues (`FAILED_SCHEDULING`), backoffs, and pull errors.
6. **Issue Deduplication**: `IssueDeduplicator` provides thread-safe `Set`-backed key deduplication (`namespace/name/reason`) and cleans up on pod deletion.
7. **Automated Unit Testing**: Comprehensive unit tests covering `BadStateReasonTest` and `IssueDeduplicatorTest`.

## Current Implementation

- `src/main/java/com/sharan/kubeon/KubeonApplication.java`: Spring Boot main entry point.
- `src/main/java/com/sharan/kubeon/config/KubernetesClientConfig.java`: Spring `@Configuration` defining `KubernetesClient` bean using `KubernetesClientBuilder`.
- `src/main/java/com/sharan/kubeon/kubernetes/ClusterConnectivityCheck.java`: `CommandLineRunner` listing pods cluster-wide on startup.
- `src/main/java/com/sharan/kubeon/detection/BadStateReason.java`: Enum matching Kubernetes container status reasons and Event reasons.
- `src/main/java/com/sharan/kubeon/detection/DetectedIssue.java`: Immutable record holding namespace, podName, reason, timestamp, and source message.
- `src/main/java/com/sharan/kubeon/detection/IssueDeduplicator.java`: Shared deduplicator across watchers to prevent duplicate alerts.
- `src/main/java/com/sharan/kubeon/kubernetes/watcher/PodWatcher.java`: Streaming watch on Kubernetes Pods.
- `src/main/java/com/sharan/kubeon/kubernetes/watcher/EventWatcher.java`: Streaming watch on Kubernetes Events.
- `src/test/java/com/sharan/kubeon/detection/BadStateReasonTest.java`: Unit tests for reason mapping and case insensitivity.
- `src/test/java/com/sharan/kubeon/detection/IssueDeduplicatorTest.java`: Unit tests for thread-safe deduplication and deletion cleanup.
- `demo-test-files/oom-test.yaml`: Test pod specification running `polinux/stress` with memory limit.

## Verified Working

- All 7 tests pass clean (`.\mvnw.cmd clean test`).
- Spring Boot component scanning correctly discovers all beans across `config`, `kubernetes`, `kubernetes.watcher`, and `detection`.
- Fabric8 client connects to local Minikube cluster (v1.34.0, node `minikube`) and `ClusterConnectivityCheck` prints active pods.
- `PodWatcher` and `EventWatcher` both start and operate as expected after refactoring.

## Minikube Verification

- `kubectl get nodes`: node `minikube` is `Ready`, control-plane, v1.34.0.
- `kubectl get pods -A`: cluster is reachable and healthy.

## Current Problem / Blocker

None. The package reorganization is clean and verified.

## Last Completed Step

Reorganized classes into `config`, `kubernetes.watcher`, and `detection` packages, verified with `.\mvnw.cmd clean test`.

## Next Step

Commit package refactor with message "Refactor project package structure", then proceed to Phase 3 (Evidence gathering: `EvidenceBundle` and `EvidenceCollector` under `com.sharan.kubeon.kubernetes.evidence`).

## Important Technical Decisions

- Package convention established:
  - `com.sharan.kubeon`: root application class.
  - `com.sharan.kubeon.config`: configuration beans.
  - `com.sharan.kubeon.kubernetes`: cluster interaction logic and watchers.
  - `com.sharan.kubeon.kubernetes.watcher`: pod and event watchers.
  - `com.sharan.kubeon.kubernetes.evidence`: evidence gathering (Phase 3).
  - `com.sharan.kubeon.detection`: anomaly detection models and deduplication.
- Tests strictly mirror the production package layout.

## Recently Changed Files

- `src/main/java/com/sharan/kubeon/config/KubernetesClientConfig.java`
- `src/main/java/com/sharan/kubeon/kubernetes/ClusterConnectivityCheck.java`
- `src/main/java/com/sharan/kubeon/kubernetes/watcher/PodWatcher.java`
- `src/main/java/com/sharan/kubeon/kubernetes/watcher/EventWatcher.java`
- `src/main/java/com/sharan/kubeon/detection/BadStateReason.java`
- `src/main/java/com/sharan/kubeon/detection/DetectedIssue.java`
- `src/main/java/com/sharan/kubeon/detection/IssueDeduplicator.java`
- `src/test/java/com/sharan/kubeon/detection/BadStateReasonTest.java`
- `src/test/java/com/sharan/kubeon/detection/IssueDeduplicatorTest.java`
- `PROJECT_PROGRESS.md`

## Tests / Commands

- `.\mvnw.cmd clean test`: All 7 tests passing.

## Git State

- Branch: `main`
- Latest commit: `3df0b7a Update PROJECT_PROGRESS.md with latest commit checkpoint`
- Uncommitted changes: File renames and package updates across `src/main/java`, `src/test/java`, and `PROJECT_PROGRESS.md`

## Session Notes

- Pure organizational refactoring without functional alterations.
- Everything builds, starts, and passes. Ready for commit.
