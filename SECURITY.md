# Security Policy

## Supported Versions

| Version | Supported |
|---------|-----------|
| 0.0.1   | ✅ Yes    |

## Reporting a Vulnerability

**Do NOT create a public GitHub issue for security vulnerabilities.**

If you discover a security vulnerability in Kubeon, please report it responsibly:

1. **Email:** Send details to [sharansc482@gmail.com](mailto:sharansc482@gmail.com)
2. **Include:**
   - Description of the vulnerability
   - Steps to reproduce
   - Potential impact
   - Suggested fix (if any)

## What to Expect

- **Acknowledgment** within 48 hours of your report
- **Assessment** of severity and impact within 1 week
- **Fix timeline** communicated based on severity:
  - Critical: patch within 7 days
  - High: patch within 14 days
  - Medium/Low: addressed in next release
- **Credit** in the release notes (unless you prefer anonymity)

## Security Considerations

Kubeon handles sensitive Kubernetes cluster data. The project includes:

- **Sensitive data redaction** — Secrets, tokens, and credentials are automatically redacted before reaching the LLM, database, API responses, or Slack notifications
- **Least-privilege RBAC** — The Kubernetes ClusterRole grants only read-only access (get, list, watch)
- **No host kubeconfig mounting** — In-cluster deployment uses ServiceAccount tokens
- **Secret management** — All credentials are injected via environment variables or Kubernetes Secrets, never hardcoded

## Scope

The following are in scope for security reports:

- Secret leakage through logs, API responses, Slack notifications, or LLM prompts
- RBAC escalation beyond read-only permissions
- Authentication or authorization bypasses in the REST API
- Dependency vulnerabilities with known exploits
