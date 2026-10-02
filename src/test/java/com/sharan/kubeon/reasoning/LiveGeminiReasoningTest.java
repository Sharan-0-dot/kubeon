package com.sharan.kubeon.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.detection.BadStateReason;
import com.sharan.kubeon.detection.DetectedIssue;
import com.sharan.kubeon.kubernetes.evidence.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class LiveGeminiReasoningTest {

    private static String apiKey;
    private static String modelName = "gemini-2.5-flash";

    @BeforeAll
    static void loadCredentials() {
        Path envPath = Paths.get(".env");
        if (Files.exists(envPath)) {
            try {
                for (String line : Files.readAllLines(envPath)) {
                    if (line.startsWith("GEMINI_API_KEY=")) {
                        apiKey = line.substring("GEMINI_API_KEY=".length()).trim();
                    }
                    if (line.startsWith("GEMINI_MODEL=")) {
                        modelName = line.substring("GEMINI_MODEL=".length()).trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("GEMINI_API_KEY");
        }
    }

    @Test
    void testLiveReasoningPipelineWithRealGemini() {
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "Skipping live test because GEMINI_API_KEY is not set");

        ChatModel chatModel = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.1)
                .responseFormat(ResponseFormat.JSON)
                .timeout(Duration.ofSeconds(30))
                .build();

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ReasoningAgent agent = new ReasoningAgent(chatModel, mapper, modelName);

        // Build a sample evidence bundle matching our live Minikube OOM incident
        DetectedIssue issue = new DetectedIssue("default", "oom-test", BadStateReason.OOM_KILLED, Instant.now(), "Container terminated with OOMKilled");
        PodSpecSummary spec = new PodSpecSummary(
                "oom-test",
                "default",
                "minikube",
                "Running",
                "Always",
                List.of(new ContainerSummary("stress", "polinux/stress", Map.of(), Map.of("memory", "50Mi"), false, false, false, false)),
                Map.of()
        );
        EventSummary event = new EventSummary("Warning", "BackOff", "Back-off restarting failed container", 5, null, null, "kubelet");
        LogSnapshot log = new LogSnapshot("stress", true, "stress: info: [1] dispatching 1 runner\nworker 7 got signal 9", false, null);

        EvidenceBundle bundle = new EvidenceBundle(issue, spec, List.of(event), List.of(log), Instant.now());

        Diagnosis diagnosis = agent.diagnose(bundle);

        System.out.println("=== LIVE GEMINI REASONING RESULT ===");
        System.out.println("Model used: " + diagnosis.modelUsed());
        System.out.println("Confidence: " + diagnosis.confidence());
        System.out.println("Root Cause: " + diagnosis.rootCauseHypothesis());
        System.out.println("Suggested Fix: " + diagnosis.suggestedFix());
        System.out.println("====================================");

        assertNotNull(diagnosis);
        assertNotNull(diagnosis.rootCauseHypothesis());
        assertFalse(diagnosis.rootCauseHypothesis().isBlank());
        assertNotNull(diagnosis.suggestedFix());
        assertEquals(modelName, diagnosis.modelUsed());
    }
}
