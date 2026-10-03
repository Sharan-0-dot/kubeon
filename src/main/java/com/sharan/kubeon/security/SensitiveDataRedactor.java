package com.sharan.kubeon.security;

import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.kubernetes.evidence.ContainerSummary;
import com.sharan.kubeon.kubernetes.evidence.EventSummary;
import com.sharan.kubeon.kubernetes.evidence.EvidenceBundle;
import com.sharan.kubeon.kubernetes.evidence.LogSnapshot;
import com.sharan.kubeon.kubernetes.evidence.PodSpecSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SensitiveDataRedactor is the centralized security perimeter component responsible for
 * identifying and masking sensitive cluster credentials, API keys, private keys, tokens,
 * passwords, and connection strings before evidence is sent to external LLMs, persisted
 * to storage, or output via REST API / Slack notifications.
 */
@Component
public class SensitiveDataRedactor {

    private static final Logger log = LoggerFactory.getLogger(SensitiveDataRedactor.class);

    public static final String REDACTED_MARKER = "[REDACTED]";

    // Benign non-secret values that match key patterns but must NOT be redacted
    private static final Set<String> BENIGN_VALUES = Set.of(
            "true", "false", "null", "none", "<none>", "nil",
            "[redacted]", "undefined", "empty", "disabled", "enabled"
    );

    // 1. Private keys (PEM / OpenSSH blocks)
    private static final Pattern PRIVATE_KEY_PATTERN = Pattern.compile(
            "(?s)-----BEGIN [A-Z\\s]+PRIVATE KEY-----.*?-----END [A-Z\\s]+PRIVATE KEY-----"
    );

    // 2. URIs with embedded passwords (e.g. postgres://user:password@host:5432/db)
    private static final Pattern URI_CREDENTIALS_PATTERN = Pattern.compile(
            "([a-zA-Z][a-zA-Z0-9+.-]*://)([^:\\s/@]+):([^\\s/]+)@([a-zA-Z0-9_.-]+(?::[0-9]+)?(?:/[^\\s]*)?)"
    );

    // 3. Authorization headers (Bearer / Basic tokens)
    private static final Pattern AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)\\b(Authorization\\s*:\\s*(?:Bearer|Basic)\\s+)[A-Za-z0-9\\-._~+/]+=*"
    );

    // 4. Standalone Bearer tokens
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "(?i)\\b(Bearer\\s+)[A-Za-z0-9\\-._~+/]{15,}"
    );

    // 5. JSON Web Tokens (JWT) / K8s service-account tokens (three base64url segments separated by dots)
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"
    );

    // 6. Known specific token formats
    private static final Pattern GOOGLE_API_KEY_PATTERN = Pattern.compile(
            "\\bAIza[0-9A-Za-z\\-_]{30,40}\\b"
    );

    private static final Pattern GITHUB_TOKEN_PATTERN = Pattern.compile(
            "\\b(gh[pousr]_[A-Za-z0-9_]{36,}|github_pat_[A-Za-z0-9_]{82})\\b"
    );

    private static final Pattern SLACK_TOKEN_PATTERN = Pattern.compile(
            "\\bxox[baprs]-[0-9a-zA-Z]{10,48}\\b"
    );

    private static final Pattern SLACK_WEBHOOK_PATTERN = Pattern.compile(
            "https://hooks\\.slack\\.com/services/T[a-zA-Z0-9_]+/B[a-zA-Z0-9_]+/[a-zA-Z0-9_]+"
    );

    private static final Pattern AWS_KEY_PATTERN = Pattern.compile(
            "\\b(AKIA|ASIA|AROA)[0-9A-Z]{16}\\b"
    );

    // 7. Key-Value credential pairs (e.g. password=..., api_key: ..., DB_PASSWORD="...")
    private static final Pattern KEY_VALUE_CREDENTIAL_PATTERN = Pattern.compile(
            "(?i)\\b([A-Za-z0-9_.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|auth[_-]?token|client[_-]?secret)[A-Za-z0-9_.-]*)\\b(\\s*[:=]\\s*)(?:([\"'])(.*?)\\3|([^\"'\\s,;&]+))"
    );

    // Sensitive label keys to mask
    private static final Pattern SENSITIVE_KEY_NAME_PATTERN = Pattern.compile(
            "(?i).*(password|passwd|secret|token|api[_-]?key|private[_-]?key|credential|auth).*"
    );

    private final boolean enabled;

    public SensitiveDataRedactor(@Value("${kubeon.redaction.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Redacts sensitive information from an entire EvidenceBundle, returning a new immutable
     * sanitized copy without mutating the original.
     */
    public EvidenceBundle redact(EvidenceBundle bundle) {
        if (!enabled || bundle == null) {
            return bundle;
        }

        DetectedIssue sanitizedIssue = redactIssue(bundle.issue());
        PodSpecSummary sanitizedPodSpec = redactPodSpec(bundle.podSpec());
        List<EventSummary> sanitizedEvents = redactEvents(bundle.recentEvents());
        List<LogSnapshot> sanitizedLogs = redactLogs(bundle.logs());

        log.debug("Sanitized evidence bundle for {}/{}",
                bundle.issue() != null ? bundle.issue().namespace() : "unknown",
                bundle.issue() != null ? bundle.issue().podName() : "unknown");

        return new EvidenceBundle(
                sanitizedIssue,
                sanitizedPodSpec,
                sanitizedEvents,
                sanitizedLogs,
                bundle.collectedAt()
        );
    }

    /**
     * Redacts sensitive information from free-form text such as container logs, event messages,
     * tool outputs, and LLM prompts.
     */
    public String redactText(String text) {
        if (!enabled || text == null || text.isBlank()) {
            return text;
        }

        String result = text;

        // 1. Private keys
        result = PRIVATE_KEY_PATTERN.matcher(result).replaceAll(REDACTED_MARKER);

        // 2. URI credentials (replace password portion)
        result = URI_CREDENTIALS_PATTERN.matcher(result).replaceAll("$1$2:" + REDACTED_MARKER + "@$4");

        // 3. Authorization headers
        result = AUTH_HEADER_PATTERN.matcher(result).replaceAll("$1" + REDACTED_MARKER);

        // 4. Standalone Bearer tokens
        result = BEARER_TOKEN_PATTERN.matcher(result).replaceAll("$1" + REDACTED_MARKER);

        // 5. JWT and Service Account tokens
        result = JWT_PATTERN.matcher(result).replaceAll(REDACTED_MARKER);

        // 6. Specific secret formats
        result = GOOGLE_API_KEY_PATTERN.matcher(result).replaceAll(REDACTED_MARKER);
        result = GITHUB_TOKEN_PATTERN.matcher(result).replaceAll(REDACTED_MARKER);
        result = SLACK_TOKEN_PATTERN.matcher(result).replaceAll(REDACTED_MARKER);
        result = SLACK_WEBHOOK_PATTERN.matcher(result).replaceAll(REDACTED_MARKER);
        result = AWS_KEY_PATTERN.matcher(result).replaceAll(REDACTED_MARKER);

        // 7. Key-value credential pairs
        result = redactKeyValueCredentials(result);

        return result;
    }

    private String redactKeyValueCredentials(String input) {
        Matcher matcher = KEY_VALUE_CREDENTIAL_PATTERN.matcher(input);
        if (!matcher.find()) {
            return input;
        }

        StringBuilder sb = new StringBuilder();
        do {
            String key = matcher.group(1);
            String delimiter = matcher.group(2);
            String quote = matcher.group(3) != null ? matcher.group(3) : "";
            String value = matcher.group(4) != null ? matcher.group(4) : matcher.group(5);

            if (isBenignValue(value)) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            } else {
                String replacement = key + delimiter + quote + REDACTED_MARKER + quote;
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
        } while (matcher.find());
        matcher.appendTail(sb);

        return sb.toString();
    }

    private boolean isBenignValue(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return BENIGN_VALUES.contains(value.toLowerCase().trim());
    }

    public DetectedIssue redactIssue(DetectedIssue issue) {
        if (!enabled || issue == null) {
            return issue;
        }
        String sanitizedMessage = redactText(issue.sourceMessage());
        return new DetectedIssue(
                issue.namespace(),
                issue.podName(),
                issue.reason(),
                issue.detectedAt(),
                sanitizedMessage
        );
    }

    public PodSpecSummary redactPodSpec(PodSpecSummary podSpec) {
        if (!enabled || podSpec == null) {
            return podSpec;
        }

        Map<String, String> sanitizedLabels = new HashMap<>();
        if (podSpec.labels() != null) {
            for (Map.Entry<String, String> entry : podSpec.labels().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if (k != null && SENSITIVE_KEY_NAME_PATTERN.matcher(k).matches()) {
                    sanitizedLabels.put(k, REDACTED_MARKER);
                } else {
                    sanitizedLabels.put(k, redactText(v));
                }
            }
        }

        List<ContainerSummary> sanitizedContainers = new ArrayList<>();
        if (podSpec.containers() != null) {
            for (ContainerSummary container : podSpec.containers()) {
                sanitizedContainers.add(new ContainerSummary(
                        container.name(),
                        redactText(container.image()),
                        container.requests(),
                        container.limits(),
                        container.hasLivenessProbe(),
                        container.hasReadinessProbe(),
                        container.hasStartupProbe(),
                        container.isInit()
                ));
            }
        }

        return new PodSpecSummary(
                podSpec.podName(),
                podSpec.namespace(),
                podSpec.nodeName(),
                podSpec.phase(),
                podSpec.restartPolicy(),
                sanitizedContainers,
                sanitizedLabels
        );
    }

    public List<EventSummary> redactEvents(List<EventSummary> events) {
        if (!enabled || events == null) {
            return events;
        }
        List<EventSummary> sanitized = new ArrayList<>(events.size());
        for (EventSummary event : events) {
            if (event == null) {
                continue;
            }
            sanitized.add(new EventSummary(
                    event.type(),
                    event.reason(),
                    redactText(event.message()),
                    event.count(),
                    event.firstTimestamp(),
                    event.lastTimestamp(),
                    event.sourceComponent()
            ));
        }
        return sanitized;
    }

    public List<LogSnapshot> redactLogs(List<LogSnapshot> logs) {
        if (!enabled || logs == null) {
            return logs;
        }
        List<LogSnapshot> sanitized = new ArrayList<>(logs.size());
        for (LogSnapshot log : logs) {
            if (log == null) {
                continue;
            }
            sanitized.add(new LogSnapshot(
                    log.containerName(),
                    log.previous(),
                    redactText(log.logContent()),
                    log.truncated(),
                    redactText(log.errorMessage())
            ));
        }
        return sanitized;
    }
}
