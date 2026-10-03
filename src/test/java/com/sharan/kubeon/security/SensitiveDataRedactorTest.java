package com.sharan.kubeon.security;

import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.kubernetes.evidence.ContainerSummary;
import com.sharan.kubeon.kubernetes.evidence.EventSummary;
import com.sharan.kubeon.kubernetes.evidence.EvidenceBundle;
import com.sharan.kubeon.kubernetes.evidence.LogSnapshot;
import com.sharan.kubeon.kubernetes.evidence.PodSpecSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataRedactorTest {

    private SensitiveDataRedactor redactor;

    @BeforeEach
    void setUp() {
        redactor = new SensitiveDataRedactor(true);
    }

    @Test
    void testRedactPrivateKeys() {
        String rsaKey = """
                -----BEGIN RSA PRIVATE KEY-----
                MIIEowIBAAKCAQEA0+k4k3bZ9...fake...key...content...==
                -----END RSA PRIVATE KEY-----
                """;
        String logMessage = "Application crashed with private key:\n" + rsaKey + "\nCheck keystore.";
        String sanitized = redactor.redactText(logMessage);

        assertThat(sanitized).doesNotContain("MIIEowIBAAKCAQEA0");
        assertThat(sanitized).contains("[REDACTED]");
        assertThat(sanitized).contains("Application crashed with private key:");
        assertThat(sanitized).contains("Check keystore.");
    }

    @Test
    void testRedactUriCredentials() {
        String log1 = "Connecting to database at postgres://admin:superSecret123@postgres.prod:5432/kubeon";
        String sanitized1 = redactor.redactText(log1);

        assertThat(sanitized1).isEqualTo("Connecting to database at postgres://admin:[REDACTED]@postgres.prod:5432/kubeon");
        assertThat(sanitized1).doesNotContain("superSecret123");

        String log2 = "Failed to connect: mongodb://app_user:dbPasswordP@ss@mongo-primary:27017/analytics";
        String sanitized2 = redactor.redactText(log2);

        assertThat(sanitized2).isEqualTo("Failed to connect: mongodb://app_user:[REDACTED]@mongo-primary:27017/analytics");
        assertThat(sanitized2).doesNotContain("dbPasswordP@ss");
    }

    @Test
    void testRedactAuthorizationHeaders() {
        String log1 = "HTTP Request header: Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.fake.signature";
        String sanitized1 = redactor.redactText(log1);

        assertThat(sanitized1).doesNotContain("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9");
        assertThat(sanitized1).contains("Authorization: Bearer [REDACTED]");

        String log2 = "HTTP Request header: Authorization: Basic dXNlcm5hbWU6cGFzc3dvcmQ=";
        String sanitized2 = redactor.redactText(log2);

        assertThat(sanitized2).isEqualTo("HTTP Request header: Authorization: Basic [REDACTED]");
    }

    @Test
    void testRedactBearerAndJwtTokens() {
        String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String text = "Service account token used: " + jwt;
        String sanitized = redactor.redactText(text);

        assertThat(sanitized).doesNotContain(jwt);
        assertThat(sanitized).isEqualTo("Service account token used: [REDACTED]");

        String bearerText = "Request with Bearer sec_tok_1234567890abcdef failed";
        String sanitizedBearer = redactor.redactText(bearerText);

        assertThat(sanitizedBearer).isEqualTo("Request with Bearer [REDACTED] failed");
    }

    @Test
    void testRedactKnownTokenFormats() {
        // Google AI / API Key
        String text1 = "Using Google API key AIzaSyD-1234567890abcdefghijklmnopqr to call Gemini";
        assertThat(redactor.redactText(text1)).isEqualTo("Using Google API key [REDACTED] to call Gemini");

        // GitHub PAT
        String text2 = "Cloning with token ghp_1234567890abcdefghijklmnopqrstuvwxyzAB from GitHub";
        assertThat(redactor.redactText(text2)).isEqualTo("Cloning with token [REDACTED] from GitHub");

        // Slack Bot Token
        String text3 = "Configured slack bot token: " + "xoxb-" + "mocktoken123456789";
        assertThat(redactor.redactText(text3)).isEqualTo("Configured slack bot token: [REDACTED]");

        // Slack Webhook URL
        String text4 = "Webhook URL: " + "https://" + "hooks.slack.com/services/T00000000/B00000000/mockSecretToken0000";
        assertThat(redactor.redactText(text4)).isEqualTo("Webhook URL: [REDACTED]");

        // AWS Access Key ID
        String text5 = "AWS credentials: AKIAIOSFODNN7EXAMPLE";
        assertThat(redactor.redactText(text5)).isEqualTo("AWS credentials: [REDACTED]");
    }

    @Test
    void testRedactKeyValueCredentials() {
        String log = """
                DB_PASSWORD="superSecretPassword!"
                password: secret123
                client_secret=my-client-secret-999
                api_key: 'top_secret_api_key'
                """;

        String sanitized = redactor.redactText(log);

        assertThat(sanitized).doesNotContain("superSecretPassword!");
        assertThat(sanitized).doesNotContain("secret123");
        assertThat(sanitized).doesNotContain("my-client-secret-999");
        assertThat(sanitized).doesNotContain("top_secret_api_key");

        assertThat(sanitized).contains("DB_PASSWORD=\"[REDACTED]\"");
        assertThat(sanitized).contains("password: [REDACTED]");
        assertThat(sanitized).contains("client_secret=[REDACTED]");
        assertThat(sanitized).contains("api_key: '[REDACTED]'");
    }

    @Test
    void testFalsePositiveResistanceForKubernetesData() {
        String k8sLog = """
                Pod phase: Running
                Reason: OOMKilled
                CrashLoopBackOff detected for container web
                ImagePullBackOff for repository docker.io/library/nginx:latest
                Readiness probe failed: HTTP probe failed with statuscode: 500
                Resource limits: CPU: 100m, Memory: 50Mi
                Namespace: default, Pod: oom-test
                Container exitCode: 137
                hasReadinessProbe: false
                hasLivenessProbe: true
                secret: null
                token: [REDACTED]
                """;

        String sanitized = redactor.redactText(k8sLog);

        assertThat(sanitized).contains("Reason: OOMKilled");
        assertThat(sanitized).contains("CrashLoopBackOff detected for container web");
        assertThat(sanitized).contains("ImagePullBackOff for repository docker.io/library/nginx:latest");
        assertThat(sanitized).contains("Readiness probe failed: HTTP probe failed with statuscode: 500");
        assertThat(sanitized).contains("Resource limits: CPU: 100m, Memory: 50Mi");
        assertThat(sanitized).contains("Namespace: default, Pod: oom-test");
        assertThat(sanitized).contains("Container exitCode: 137");
        assertThat(sanitized).contains("hasReadinessProbe: false");
        assertThat(sanitized).contains("hasLivenessProbe: true");
        assertThat(sanitized).contains("secret: null");
    }

    @Test
    void testRedactEvidenceBundleImmutabilityAndContent() {
        DetectedIssue issue = new DetectedIssue(
                "prod",
                "app-pod",
                BadStateReason.CRASH_LOOP_BACKOFF,
                Instant.now(),
                "Failed with password=rootPass123"
        );

        PodSpecSummary podSpec = new PodSpecSummary(
                "app-pod",
                "prod",
                "node-1",
                "Running",
                "Always",
                List.of(new ContainerSummary("app", "my-registry.io/app:v1", Map.of(), Map.of(), false, false, false, false)),
                Map.of("app", "web", "api_key", "secret-label-token")
        );

        EventSummary event = new EventSummary(
                "Warning",
                "FailedMount",
                "Error mounting volume with uri: postgres://db:secretDbPass@db.internal:5432/app",
                1,
                "2026-10-03T10:00:00Z",
                "2026-10-03T10:00:00Z",
                "kubelet"
        );

        LogSnapshot logSnapshot = new LogSnapshot(
                "app",
                false,
                "Error on startup: Authorization: Bearer secretBearer12345\nDB_PASSWORD=\"myPass999\"",
                false,
                null
        );

        EvidenceBundle original = new EvidenceBundle(
                issue,
                podSpec,
                List.of(event),
                List.of(logSnapshot),
                Instant.now()
        );

        EvidenceBundle sanitized = redactor.redact(original);

        // Verify sanitized copy
        assertThat(sanitized).isNotSameAs(original);
        assertThat(sanitized.issue().sourceMessage()).isEqualTo("Failed with password=[REDACTED]");
        assertThat(sanitized.podSpec().labels().get("api_key")).isEqualTo("[REDACTED]");
        assertThat(sanitized.recentEvents().get(0).message()).contains("postgres://db:[REDACTED]@db.internal:5432/app");
        assertThat(sanitized.logs().get(0).logContent()).contains("Authorization: Bearer [REDACTED]");
        assertThat(sanitized.logs().get(0).logContent()).contains("DB_PASSWORD=\"[REDACTED]\"");

        // Verify original was untouched (immutable)
        assertThat(original.issue().sourceMessage()).isEqualTo("Failed with password=rootPass123");
        assertThat(original.podSpec().labels().get("api_key")).isEqualTo("secret-label-token");
        assertThat(original.recentEvents().get(0).message()).contains("secretDbPass");
        assertThat(original.logs().get(0).logContent()).contains("secretBearer12345");
    }

    @Test
    void testDisabledRedactor() {
        SensitiveDataRedactor disabledRedactor = new SensitiveDataRedactor(false);

        String text = "password=secret123 Authorization: Bearer myToken";
        assertThat(disabledRedactor.redactText(text)).isEqualTo(text);

        EvidenceBundle bundle = new EvidenceBundle(
                new DetectedIssue("default", "pod", BadStateReason.CRASH_LOOP_BACKOFF, Instant.now(), "password=123"),
                null, List.of(), List.of(), Instant.now()
        );
        assertThat(disabledRedactor.redact(bundle)).isSameAs(bundle);
    }
}
