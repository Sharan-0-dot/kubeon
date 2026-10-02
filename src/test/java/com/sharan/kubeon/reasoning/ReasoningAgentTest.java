package com.sharan.kubeon.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.agent.tools.KubernetesEvidenceTools;
import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.kubernetes.evidence.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReasoningAgentTest {

    @Mock
    private ChatModel chatModel;

    @Mock
    private KubernetesEvidenceTools kubernetesEvidenceTools;

    private ObjectMapper objectMapper;
    private ReasoningAgent agent;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        agent = new ReasoningAgent(chatModel, objectMapper, kubernetesEvidenceTools, "gemini-2.0-flash", 3);
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
    void testDiagnoseStructuredOutputSuccessfulWithoutTools() {
        String llmJsonResponse = """
                {
                  "rootCauseHypothesis": "Container exceeded its 50Mi memory limit during high allocation.",
                  "confidence": "HIGH",
                  "suggestedFix": "Increase the container memory limit to at least 256Mi in the pod specification."
                }
                """;

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(llmJsonResponse))
                .build();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        EvidenceBundle bundle = createBundle(BadStateReason.OOM_KILLED, "oom-pod", "signal 9 OutOfMemory");

        Diagnosis diagnosis = agent.diagnose(bundle);

        assertThat(diagnosis).isNotNull();
        assertThat(diagnosis.rootCauseHypothesis()).contains("Container exceeded its 50Mi memory limit");
        assertThat(diagnosis.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(diagnosis.suggestedFix()).contains("Increase the container memory limit");
        assertThat(diagnosis.modelUsed()).isEqualTo("gemini-2.0-flash");
        assertThat(diagnosis.toolCallsUsed()).isEmpty();

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(requestCaptor.capture());
        ChatRequest captured = requestCaptor.getValue();
        UserMessage userMsg = (UserMessage) captured.messages().get(0);
        assertThat(userMsg.singleText()).contains("oom-pod");
        assertThat(userMsg.singleText()).contains("OOM_KILLED");
    }

    @Test
    void testAgenticInvestigationWithToolCall() {
        // Step 1: Model requests a tool call to get additional pod details
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-1")
                .name("getPodDetails")
                .arguments("{\"namespace\":\"default\",\"podName\":\"crash-pod\"}")
                .build();

        ChatResponse firstResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(toolRequest))
                .build();

        // Step 2: Model receives tool result and produces final diagnosis
        String finalJson = """
                {
                  "rootCauseHypothesis": "Container terminated with exit code 137 (OOMKilled) confirmed from live pod details.",
                  "confidence": "HIGH",
                  "suggestedFix": "Increase container memory limits in the deployment manifest."
                }
                """;

        ChatResponse secondResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(finalJson))
                .build();

        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(firstResponse)
                .thenReturn(secondResponse);

        when(kubernetesEvidenceTools.getPodDetails("default", "crash-pod"))
                .thenReturn("Pod: default/crash-pod\nLast State: Terminated (ExitCode: 137, Reason: OOMKilled)");

        EvidenceBundle bundle = createBundle(BadStateReason.CRASH_LOOP_BACKOFF, "crash-pod", null);

        Diagnosis diagnosis = agent.diagnose(bundle);

        assertThat(diagnosis.rootCauseHypothesis()).contains("exit code 137 (OOMKilled)");
        assertThat(diagnosis.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(diagnosis.toolCallsUsed()).containsExactly("getPodDetails");

        verify(kubernetesEvidenceTools).getPodDetails("default", "crash-pod");
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void testMaxToolCallsEnforced() {
        // Model keeps asking for tools indefinitely
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-loop")
                .name("getEvents")
                .arguments("{\"namespace\":\"default\",\"podName\":\"loop-pod\"}")
                .build();

        ChatResponse toolResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(toolRequest))
                .build();

        String finalJson = """
                {
                  "rootCauseHypothesis": "Best hypothesis based on partial events.",
                  "confidence": "LOW",
                  "suggestedFix": "Inspect the pod logs manually."
                }
                """;

        ChatResponse finalResponse = ChatResponse.builder()
                .aiMessage(AiMessage.from(finalJson))
                .build();

        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(toolResponse) // iteration 1
                .thenReturn(toolResponse) // iteration 2
                .thenReturn(toolResponse) // iteration 3 (max reached)
                .thenReturn(finalResponse); // final prompt call

        when(kubernetesEvidenceTools.getEvents("default", "loop-pod")).thenReturn("Event details...");

        EvidenceBundle bundle = createBundle(BadStateReason.CRASH_LOOP_BACKOFF, "loop-pod", null);

        Diagnosis diagnosis = agent.diagnose(bundle);

        assertThat(diagnosis.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(diagnosis.toolCallsUsed()).hasSize(3);
        assertThat(diagnosis.toolCallsUsed()).containsOnly("getEvents");

        // 3 loop calls + 1 concluding call = 4 total chat calls
        verify(chatModel, times(4)).chat(any(ChatRequest.class));
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

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(wrappedJson))
                .build();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        EvidenceBundle bundle = createBundle(BadStateReason.CRASH_LOOP_BACKOFF, "crash-pod", "java.lang.NullPointerException");

        Diagnosis diagnosis = agent.diagnose(bundle);

        assertThat(diagnosis.rootCauseHypothesis()).contains("unhandled NullPointerException");
        assertThat(diagnosis.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(diagnosis.suggestedFix()).contains("database connection parameters");
    }

    @Test
    void testUnstructuredOutputGracefulFallback() {
        String plainText = "The pod crashed because it ran out of memory. Try increasing the resources.";

        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(plainText))
                .build();

        when(chatModel.chat(any(ChatRequest.class))).thenReturn(response);

        EvidenceBundle bundle = createBundle(BadStateReason.OOM_KILLED, "fallback-pod", "OOMKilled");

        Diagnosis diagnosis = agent.diagnose(bundle);

        assertThat(diagnosis.rootCauseHypothesis()).isEqualTo(plainText);
        assertThat(diagnosis.confidence()).isEqualTo(ConfidenceLevel.LOW);
        assertThat(diagnosis.suggestedFix()).contains("Manual verification recommended");
    }

    @Test
    void testMissingApiKeyThrowsIllegalStateException() {
        ReasoningAgent unconfiguredAgent = new ReasoningAgent(null, objectMapper, kubernetesEvidenceTools, "gemini-2.0-flash", 3);
        EvidenceBundle bundle = createBundle(BadStateReason.IMAGE_PULL_BACK_OFF, "image-pod", null);

        assertThatThrownBy(() -> unconfiguredAgent.diagnose(bundle))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GEMINI_API_KEY");
    }
}
