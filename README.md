# Kubeon

[![CI](https://github.com/Sharan-0-dot/kubeon/actions/workflows/ci.yml/badge.svg)](https://github.com/Sharan-0-dot/kubeon/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-blue)
![Spring Boot 4.1.1](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**AI-powered Kubernetes incident detection, investigation, and diagnosis.** Kubeon watches your cluster for failures — OOMKilled containers, CrashLoopBackOff, image pull errors, probe failures, scheduling problems — gathers deterministic evidence, and uses Google Gemini to diagnose root causes and suggest fixes.

---

## Features

- **Real-time failure detection** — Streaming watchers on pods and events catch `OOMKilled`, `CrashLoopBackOff`, `ImagePullBackOff`, `ErrImagePull`, unhealthy probes, and failed scheduling
- **Deterministic evidence gathering** — Collects pod specs, container status, recent events, current and previous container logs into structured evidence bundles
- **AI-powered diagnosis** — Google Gemini analyzes evidence and produces root-cause hypotheses with confidence levels and suggested fixes
- **Agentic investigation** — When initial evidence is insufficient, the reasoning agent autonomously calls Kubernetes tools to gather additional data (pod details, logs, events, deployment rollout info)
- **Incident management** — Full lifecycle tracking with PostgreSQL persistence (`DETECTED → INVESTIGATING → DIAGNOSED → RESOLVED`)
- **Manual investigation** — REST endpoint to trigger on-demand investigation of any pod
- **Slack notifications** — Rich failure alerts with diagnosis details
- **Sensitive data redaction** — Secrets, tokens, API keys, and credentials are automatically redacted before reaching the LLM, database, API, or Slack
- **Least-privilege RBAC** — Read-only Kubernetes access with no create/update/delete permissions

## Architecture

```
┌─────────────────────────┐
│   Kubernetes Cluster    │
│                         │
│  PodWatcher             │
│  EventWatcher           │
└───────────┬─────────────┘
            │ Detected Issue
            ▼
┌───────────────────────┐
│   Evidence Collector  │
│  (pod spec, events,   │
│   logs, resources)    │
└───────────┬───────────┘
            │ Evidence Bundle
            ▼
┌───────────────────────┐
│ Sensitive Data        │
│ Redactor              │
└───────────┬───────────┘
            │ Sanitized Evidence
            ▼
┌───────────────────────┐
│   Reasoning Agent     │◄──── Agentic Tool Calls
│   (Google Gemini)     │────► K8s Evidence Tools
└───────────┬───────────┘
            │ Diagnosis
            ▼
┌───────────────────────┐
│   Incident Store      │
│   (PostgreSQL)        │
└───────────┬───────────┘
            │
     ┌──────┴──────┐
     ▼             ▼
┌─────────┐  ┌──────────┐
│ REST API│  │  Slack   │
│         │  │ Notifier │
└─────────┘  └──────────┘
```

## Prerequisites

- **Java 21** (or later)
- **Maven 3.9+** (or use the included Maven wrapper)
- **Kubernetes cluster** — Minikube, kind, or any K8s cluster
- **PostgreSQL 14+** — For incident persistence
- **Google Gemini API key** — From [Google AI Studio](https://aistudio.google.com/)
- **Slack webhook URL** *(optional)* — For notifications

## Quick Start — Local Development

```bash
# 1. Clone the repository
git clone https://github.com/Sharan-0-dot/kubeon.git
cd kubeon

# 2. Configure environment
cp env.example .env
# Edit .env with your values (API keys, database credentials)

# 3. Start PostgreSQL
# Ensure PostgreSQL is running with a 'kubeon' database

# 4. Start your Kubernetes cluster
minikube start

# 5. Run Kubeon
./mvnw spring-boot:run        # Linux / macOS
.\mvnw.cmd spring-boot:run    # Windows
```

Kubeon will connect to your cluster via `~/.kube/config`, start watching for failures, and expose the REST API on `http://localhost:8080`.

## Docker Build

```bash
# Build the Docker image
docker build -t kubeon:latest .

# Run locally (for testing — connects to host network)
docker run --rm \
  --env-file .env \
  --network host \
  kubeon:latest
```

## Kubernetes Deployment

### 1. Build and load the image

```bash
# For Minikube: build inside Minikube's Docker daemon
eval $(minikube docker-env)          # Linux / macOS
minikube docker-env | Invoke-Expression  # Windows PowerShell
docker build -t kubeon:latest .
```

### 2. Configure secrets

Edit `k8s/secret.yaml` with your actual values, **or** create the secret imperatively:

```bash
kubectl create namespace kubeon

kubectl create secret generic kubeon-secrets \
  --namespace kubeon \
  --from-literal=GEMINI_API_KEY=your-key \
  --from-literal=DB_URL=jdbc:postgresql://your-host:5432/kubeon \
  --from-literal=DB_USERNAME=postgres \
  --from-literal=DB_PASSWORD=your-password \
  --from-literal=SLACK_WEBHOOK_URL=https://hooks.slack.com/services/...
```

### 3. Apply manifests

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/serviceaccount.yaml
kubectl apply -f k8s/clusterrole.yaml
kubectl apply -f k8s/clusterrolebinding.yaml
kubectl apply -f k8s/configmap.yaml
# Only if you didn't create the secret imperatively:
# kubectl apply -f k8s/secret.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
```

### 4. Verify

```bash
kubectl -n kubeon get pods
kubectl -n kubeon logs -f deployment/kubeon
```

> **Note:** Kubeon uses Fabric8's auto-detected in-cluster config via the ServiceAccount token. No kubeconfig mounting is needed.

## Configuration

| Variable | Description | Default | Required |
|---|---|---|---|
| `GEMINI_API_KEY` | Google Gemini API key | — | **Yes** |
| `GEMINI_MODEL` | Gemini model to use | `gemini-2.0-flash` | No |
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://localhost:5432/kubeon` | **Yes** |
| `DB_USERNAME` | Database username | `postgres` | No |
| `DB_PASSWORD` | Database password | — | **Yes** |
| `SLACK_WEBHOOK_URL` | Slack incoming webhook URL | — | No |
| `MAX_TOOL_CALLS` | Max agentic tool calls per investigation | `3` | No |
| `REDACTION_ENABLED` | Enable sensitive data redaction | `true` | No |

## REST API

### Health Check
```bash
curl http://localhost:8080/api/health
```

### List Incidents
```bash
curl http://localhost:8080/api/incidents
```

### Get Incident by ID
```bash
curl http://localhost:8080/api/incidents/{id}
```

### Manual Investigation
```bash
curl -X POST http://localhost:8080/api/investigate/{namespace}/{pod}
```

**Example — investigate a failing pod:**
```bash
curl -X POST http://localhost:8080/api/investigate/default/my-failing-pod
```

## Testing a Failure Scenario

Deploy a broken pod to see Kubeon in action:

```bash
# OOMKilled — container exceeds memory limit
kubectl run oom-test --image=polinux/stress \
  --limits=memory=50Mi \
  --command -- stress --vm 1 --vm-bytes 100M --vm-hang 0

# CrashLoopBackOff — container exits immediately
kubectl run crash-test --image=busybox \
  --command -- sh -c "exit 1"

# ImagePullBackOff — non-existent image
kubectl run pull-test --image=nonexistent/image:latest

# Clean up
kubectl delete pod oom-test crash-test pull-test
```

Kubeon will detect the failure, collect evidence, diagnose it with Gemini, store the incident, and optionally notify Slack.

## Project Structure

```
kubeon/
├── src/main/java/com/sharan/kubeon/
│   ├── KubeonApplication.java          # Main entry point
│   ├── agent/tools/                    # Agentic K8s investigation tools
│   ├── api/                            # REST controllers, DTOs, exception handling
│   ├── config/                         # Spring config, Gemini config, K8s client
│   ├── detection/                      # Bad-state detection, issue deduplication
│   ├── incident/                       # Incident model, persistence, service
│   ├── kubernetes/
│   │   ├── evidence/                   # Evidence models and collector
│   │   └── watcher/                    # Pod and event watchers
│   ├── notification/                   # Slack notifier
│   ├── reasoning/                      # Gemini reasoning agent, diagnosis model
│   └── security/                       # Sensitive data redactor
├── k8s/                                # Kubernetes deployment manifests
├── Dockerfile                          # Multi-stage Docker build
├── env.example                         # Environment variable template
└── pom.xml                             # Maven build configuration
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on reporting bugs, suggesting features, and submitting pull requests.

## Security

See [SECURITY.md](SECURITY.md) for our security policy and how to report vulnerabilities.

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
