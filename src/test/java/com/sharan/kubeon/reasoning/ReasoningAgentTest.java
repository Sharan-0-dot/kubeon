package com.sharan.kubeon.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.kubernetes.evidence.*;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReasoningAgentTest {

    @Mock
    private ChatModel chatModel;

    private ObjectMapper objectMapper;
    private ReasoningAgent agent;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        agent = new ReasoningAgent(chatModel, objectMapper, "gemini-2.0-flash");
    }

    private EvidenceBundle createBundle(BadStateReason reason, String podName, String logContent) {
        DetectedIssue issue = new DetectedIssue(
                "default",
                podName,
                reason,
                Instant.now(),
                "Failure event message"
        );
        PodSpecSummary podSpec = new PodSpecSummary(
                podName,
                "default",
                "minikube",
                "Running",
                "Always",
                List.of(new ContainerSummary(
                        "app",
                        "app-image:1.0",
                        Map.of("memory", "50Mi"),
                        Map.of("memory", "50Mi"),
                        true,
                        true,
                        false,
                        false
                )),
                Map.of("app", "test")
        );
        List<EventSummary> events = List.of(new EventSummary(
                "Warning",
                reason.name(),
                "Container failed with " + reason,
                3,
                "2026-10-02T10:00:00Z",
                "2026-10-02T10:05:00Z",
                "kubelet"
        ));
        List<LogSnapshot> logs = List.of(new LogSnapshot(
                "app",
                true,
                logContent,
                false,
                null
        ));

        return new EvidenceBundle(issue, podSpec, events, logs, Instant.now());
    }

    @Test
    void testDiagnoseStructuredOutputSuccessful() {
        String llmJsonResponse = """
                {
                  "rootCauseHypothesis": "Container exceeded its 50Mi memory limit during high allocation.",
                  "confidence": "HIGH",
                  "suggestedFix": "Increase the container memory limit to at least 256Mi in the pod specification."
                }
                """;

        when(chatModel.chat(anyString())).thenReturn(llmJsonResponse);

        EvidenceBundle bundle = createBundle(BadStateReason.OOM_KILLED, "oom-pod", "signal 9 OutOfMemory");

        Diagnosis diagnosis = agent.diagnose(bundle);

        assertThat(diagnosis).isNotNull();
        assertThat(diagnosis.rootCauseHypothesis()).contains("Container exceeded its 50Mi memory limit");
        assertThat(diagnosis.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(diagnosis.suggestedFix()).contains("Increase the container memory limit");
        assertThat(diagnosis.modelUsed()).isEqualTo("gemini-2.0-flash");
        assertThat(diagnosis.toolCallsUsed()).isEmpty();

        // Verify prompt captured the evidence details
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).chat(promptCaptor.capture());
        String capturedPrompt = promptCaptor.getValue();
        assertThat(capturedPrompt).contains("oom-pod");
        assertThat(capturedPrompt).contains("OOM_KILLED");
        assertThat(capturedPrompt).contains("50Mi");
        assertThat(capturedPrompt).contains("signal 9 OutOfMemory");
    }

    @Test
    void testMarkdownWrappedJsonResponse() {
        String wrappedJson = """
                ```json
                {
                  "rootCauseHypothesis": "Container crashed due to unhandled NullPointerException in Main.",
                  "confidence": "MEDIUM",
                  "suggestedFix": "Check application database connection parameters in secret."
                }
                ```
                """;

        when(chatModel.chat(anyString())).thenReturn(wrappedJson);

        EvidenceBundle bundle = createBundle(BadStateReason.CRASH_LOOP_BACKOFF, "crash-pod", "java.lang.NullPointerException");

        Diagnosis diagnosis = agent.diagnose(bundle);

        assertThat(diagnosis.rootCauseHypothesis()).contains("unhandled NullPointerException");
        assertThat(diagnosis.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(diagnosis.suggestedFix()).contains("database connection parameters");
    }

    @Test
    void testUnstructuredOutputGracefulFallback() {
        String plainText = "The pod crashed because it ran out of memory. Try increasing the resources.";

        when(chatModel.chat(anyString())).thenReturn(plainText);

        EvidenceBundle bundle = createBundle(BadStateReason.OOM_KILLED, "fallback-pod", "OOMKilled");

        Diagnosis diagnosis = agent.diagnose(bundle);

        assertThat(diagnosis.rootCauseHypothesis()).isEqualTo(plainText);
        assertThat(diagnosis.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(diagnosis.suggestedFix()).contains("Manual verification recommended");
    }

    @Test
    void testMissingApiKeyThrowsIllegalStateException() {
        ReasoningAgent unconfiguredAgent = new ReasoningAgent(null, objectMapper, "gemini-2.0-flash");
        EvidenceBundle bundle = createBundle(BadStateReason.IMAGE_PULL_BACK_OFF, "image-pod", null);

        assertThatThrownBy(() -> unconfiguredAgent.diagnose(bundle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    void testRepresentativeFailuresPromptConstruction() {
        when(chatModel.chat(anyString())).thenReturn("""
                {
                  "rootCauseHypothesis": "Image could not be pulled from registry.",
                  "confidence": "HIGH",
                  "suggestedFix": "Verify image tag and pull secrets."
                }
                """);

        EvidenceBundle bundle = createBundle(BadStateReason.IMAGE_PULL_BACK_OFF, "image-pull-pod", "Back-off pulling image");

        Diagnosis diagnosis = agent.diagnose(bundle);

        assertThat(diagnosis.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).chat(captor.capture());
        assertThat(captor.getValue()).contains("IMAGE_PULL_BACK_OFF");
        assertThat(captor.getValue()).contains("image-pull-pod");
    }
}
