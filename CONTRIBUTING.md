# Contributing to Kubeon

Thank you for your interest in contributing to Kubeon! This document provides guidelines for contributing to the project.

## Reporting Bugs

1. Check if the bug has already been reported in [GitHub Issues](https://github.com/Sharan-0-dot/kubeon/issues)
2. If not, open a new issue with:
   - A clear, descriptive title
   - Steps to reproduce the bug
   - Expected vs actual behavior
   - Your environment (Java version, K8s version, OS)
   - Relevant logs (redact any sensitive information)

## Suggesting Features

1. Open a [GitHub Issue](https://github.com/Sharan-0-dot/kubeon/issues) with the `enhancement` label
2. Describe the feature, why it's useful, and how it should work
3. If possible, outline a proposed implementation approach

## Submitting Pull Requests

1. **Fork** the repository
2. **Create a branch** from `main`:
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. **Make your changes** following the code style below
4. **Write or update tests** — all tests must pass:
   ```bash
   ./mvnw test
   ```
5. **Commit** with a clear message:
   ```bash
   git commit -m "Add brief description of the change"
   ```
6. **Push** and open a Pull Request against `main`

## Code Style

- Follow the existing code patterns and conventions in the project
- Use meaningful variable and method names
- Add Javadoc comments for public APIs
- Keep methods focused and reasonably short
- Use records for immutable data types where appropriate

## Testing Requirements

- All existing tests must continue to pass
- New features should include unit tests
- Tests should not depend on external services (use mocks for Kubernetes client, Gemini, Slack, PostgreSQL)
- Tests should not read or depend on `.env` or real credentials

## Commit Messages

Use clear, concise commit messages that describe **what** was changed:

```
Add evidence collection for init containers
Fix deduplication race condition in PodWatcher
Update RBAC ClusterRole with events/list permission
```

## Security

- **Never** commit secrets, API keys, or credentials
- If you discover a security vulnerability, see [SECURITY.md](SECURITY.md) for reporting instructions — do **not** open a public issue

## Questions?

Open a [GitHub Discussion](https://github.com/Sharan-0-dot/kubeon/discussions) or reach out via Issues.
