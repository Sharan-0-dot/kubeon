package com.sharan.kubeon.reasoning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.kubernetes.evidence.EvidenceBundle;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReasoningAgent {

    private static final Logger log = LoggerFactory.getLogger(ReasoningAgent.class);

    private static final String PROMPT_TEMPLATE = """
            You are Kubeon, an expert AI Kubernetes incident triage agent.
            Your task is to analyze the provided Kubernetes EvidenceBundle and determine the root cause of the incident.

            Follow these strict guidelines:
            1. Ground your reasoning strictly in the observed evidence (pod spec, events, previous container logs, resource limits, exit codes).
            2. Distinguish clearly between observed facts, likely explanation, and suggested remediation.
            3. Assess your confidence as LOW, MEDIUM, or HIGH based on how conclusive the evidence is:
               - HIGH: Direct conclusive evidence exists (e.g., OOM exit code 137 / signal 9 with memory limit hit, explicit CrashLoopBackOff stack trace, probe endpoint 503).
               - MEDIUM: Strong circumstantial evidence pointing to a probable cause, but without direct log confirmation.
               - LOW: Limited or ambiguous evidence.
            4. DO NOT speculate or invent causes not supported by the evidence. If the evidence is insufficient, explicitly state that evidence is insufficient and set confidence to LOW.
            5. Provide actionable, specific remediation steps in suggestedFix (e.g., specific kubectl commands, resource adjustment suggestions, probe config changes).

            You must respond ONLY with a valid JSON object matching the following structure:
            {
              "rootCauseHypothesis": "Detailed explanation of the root cause based on evidence",
              "confidence": "LOW | MEDIUM | HIGH",
              "suggestedFix": "Concrete, actionable fix recommendations"
            }

            EVIDENCE BUNDLE:
            %s
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final String modelName;

    public ReasoningAgent(@Autowired(required = false) ChatModel chatModel,
                          ObjectMapper objectMapper,
                          @Value("${kubeon.gemini.model:${GEMINI_MODEL:gemini-2.0-flash}}") String modelName) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.modelName = modelName;
    }

    public Diagnosis diagnose(EvidenceBundle bundle) {
        if (chatModel == null) {
            throw new IllegalStateException("Gemini ChatModel is not configured. Please set GEMINI_API_KEY environment variable.");
        }

        log.info("Diagnosing incident for {}/{} (reason: {}) using model: {}",
                bundle.issue().namespace(), bundle.issue().podName(), bundle.issue().reason(), modelName);

        try {
            String evidenceJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle);
            String prompt = String.format(PROMPT_TEMPLATE, evidenceJson);

            String responseText = chatModel.chat(prompt);
            Diagnosis diagnosis = parseResponse(responseText);

            log.info("DIAGNOSIS PRODUCED for {}/{}:\n  Root Cause: {}\n  Confidence: {}\n  Suggested Fix: {}",
                    bundle.issue().namespace(), bundle.issue().podName(),
                    diagnosis.rootCauseHypothesis(), diagnosis.confidence(), diagnosis.suggestedFix());

            return diagnosis;
        } catch (Exception e) {
            log.error("Failed to generate diagnosis for {}/{}: {}",
                    bundle.issue().namespace(), bundle.issue().podName(), e.getMessage(), e);
            throw new RuntimeException("Reasoning failed: " + e.getMessage(), e);
        }
    }

    private Diagnosis parseResponse(String rawResponse) {
        try {
            String json = cleanJson(rawResponse);
            var tree = objectMapper.readTree(json);

            String rootCause = tree.has("rootCauseHypothesis") ? tree.get("rootCauseHypothesis").asText() : "Unknown root cause";
            String confidenceStr = tree.has("confidence") ? tree.get("confidence").asText() : "LOW";
            String suggestedFix = tree.has("suggestedFix") ? tree.get("suggestedFix").asText() : "Inspect pod logs and events manually";

            return new Diagnosis(
                    rootCause,
                    ConfidenceLevel.fromString(confidenceStr),
                    suggestedFix,
                    List.of(),
                    modelName
            );
        } catch (Exception e) {
            log.warn("Failed to parse structured JSON from LLM response: {}. Raw response: {}", e.getMessage(), rawResponse);
            return new Diagnosis(
                    rawResponse,
                    ConfidenceLevel.LOW,
                    "Manual verification recommended due to unstructured model response.",
                    List.of(),
                    modelName
            );
        }
    }

    private String cleanJson(String text) {
        if (text == null) return "{}";
        String trimmed = text.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
