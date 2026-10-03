package com.sharan.kubeon.reasoning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sharan.kubeon.agent.tools.KubernetesEvidenceTools;
import com.sharan.kubeon.kubernetes.evidence.EvidenceBundle;
import com.sharan.kubeon.security.SensitiveDataRedactor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ReasoningAgent {

    private static final Logger log = LoggerFactory.getLogger(ReasoningAgent.class);

    private static final String PROMPT_TEMPLATE = """
            You are Kubeon, an expert AI Kubernetes incident triage agent.
            Your task is to analyze the provided Kubernetes EvidenceBundle and determine the root cause of the incident.

            You have access to read-only Kubernetes tools to gather additional evidence if the initial evidence bundle is incomplete, ambiguous, or lacks crucial context:
            - getPodDetails: Get current pod status, container states, restart counts, exit codes, and conditions.
            - getContainerLogs: Get additional log lines from a container (current or previous instance).
            - getEvents: Get recent Kubernetes events for the pod or namespace.
            - getDeploymentRolloutInfo: Inspect deployment replica status, conditions, and rollout progress.

            Investigation Guidelines:
            1. If the initial evidence is already sufficient to make a confident diagnosis (e.g. clear OOMKilled exit code 137 / signal 9 with memory limit, explicit CrashLoopBackOff stack trace, probe failure event), produce the final diagnosis immediately without calling tools.
            2. If key information is missing (e.g., container logs are missing or empty, pod restart reasons are unclear, or you need to check deployment rollout status), call the appropriate tool to retrieve the necessary evidence before diagnosing.
            3. Ground your final diagnosis strictly in observed evidence. Do not speculate or invent causes not supported by the evidence.
            4. Assess your confidence as LOW, MEDIUM, or HIGH:
               - HIGH: Direct conclusive evidence exists.
               - MEDIUM: Strong circumstantial evidence pointing to a probable cause.
               - LOW: Limited or ambiguous evidence.
            5. Provide actionable, specific remediation steps in suggestedFix.

            When you have gathered enough evidence and are ready to provide the final diagnosis, respond ONLY with a valid JSON object matching the following structure:
            {
              "rootCauseHypothesis": "Detailed explanation of the root cause based on evidence",
              "confidence": "LOW | MEDIUM | HIGH",
              "suggestedFix": "Concrete, actionable fix recommendations"
            }

            INITIAL EVIDENCE BUNDLE:
            %s
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final KubernetesEvidenceTools kubernetesEvidenceTools;
    private final SensitiveDataRedactor sensitiveDataRedactor;
    private final String modelName;
    private final int maxToolCalls;
    private final List<ToolSpecification> toolSpecifications;

    public ReasoningAgent(ChatModel chatModel,
                          ObjectMapper objectMapper,
                          KubernetesEvidenceTools kubernetesEvidenceTools,
                          String modelName,
                          int maxToolCalls) {
        this(chatModel, objectMapper, kubernetesEvidenceTools, new SensitiveDataRedactor(true), modelName, maxToolCalls);
    }

    @Autowired
    public ReasoningAgent(@Autowired(required = false) ChatModel chatModel,
                          ObjectMapper objectMapper,
                          @Autowired(required = false) KubernetesEvidenceTools kubernetesEvidenceTools,
                          @Autowired(required = false) SensitiveDataRedactor sensitiveDataRedactor,
                          @Value("${kubeon.gemini.model:${GEMINI_MODEL:gemini-2.0-flash}}") String modelName,
                          @Value("${kubeon.reasoning.max-tool-calls:3}") int maxToolCalls) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.kubernetesEvidenceTools = kubernetesEvidenceTools;
        this.sensitiveDataRedactor = sensitiveDataRedactor != null ? sensitiveDataRedactor : new SensitiveDataRedactor(true);
        this.modelName = modelName;
        this.maxToolCalls = maxToolCalls;
        this.toolSpecifications = kubernetesEvidenceTools != null
                ? ToolSpecifications.toolSpecificationsFrom(kubernetesEvidenceTools)
                : List.of();
    }

    public Diagnosis diagnose(EvidenceBundle bundle) {
        if (chatModel == null) {
            throw new IllegalStateException("Gemini ChatModel is not configured. Please set GEMINI_API_KEY environment variable.");
        }

        log.info("Diagnosing incident for {}/{} (reason: {}) using model: {}",
                bundle.issue().namespace(), bundle.issue().podName(), bundle.issue().reason(), modelName);

        try {
            EvidenceBundle sanitizedBundle = sensitiveDataRedactor.redact(bundle);
            String evidenceJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sanitizedBundle);
            String prompt = String.format(PROMPT_TEMPLATE, evidenceJson);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from(prompt));

            List<String> toolCallsUsed = new ArrayList<>();
            int iterations = 0;

            while (iterations < maxToolCalls) {
                iterations++;
                log.debug("Reasoning step {}/{} for {}/{}", iterations, maxToolCalls,
                        bundle.issue().namespace(), bundle.issue().podName());

                ChatRequest.Builder requestBuilder = ChatRequest.builder()
                        .messages(messages);

                if (!toolSpecifications.isEmpty()) {
                    requestBuilder.toolSpecifications(toolSpecifications);
                }

                ChatResponse response = chatModel.chat(requestBuilder.build());
                AiMessage aiMessage = response.aiMessage();
                messages.add(aiMessage);

                if (!aiMessage.hasToolExecutionRequests()) {
                    Diagnosis diagnosis = parseResponse(aiMessage.text(), toolCallsUsed);
                    logDiagnosis(bundle, diagnosis);
                    return diagnosis;
                }

                // Process tool calls requested by the model
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    toolCallsUsed.add(request.name());
                    String toolResult = dispatchTool(request);
                    messages.add(ToolExecutionResultMessage.from(request, toolResult));
                }
            }

            // If investigation limit reached and model still requested tools, request final conclusion
            log.warn("Investigation limit reached ({} iterations) for {}/{}. Requesting final diagnosis.",
                    maxToolCalls, bundle.issue().namespace(), bundle.issue().podName());

            messages.add(UserMessage.from(
                    "Maximum investigation tool limit reached. Please provide your best final diagnosis now based on all available evidence and tool results, as a valid JSON object matching the requested schema."));

            ChatResponse finalResponse = chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .build());

            Diagnosis diagnosis = parseResponse(finalResponse.aiMessage().text(), toolCallsUsed);
            logDiagnosis(bundle, diagnosis);
            return diagnosis;

        } catch (Exception e) {
            log.error("Failed to generate diagnosis for {}/{}: {}",
                    bundle.issue().namespace(), bundle.issue().podName(), e.getMessage(), e);
            throw new RuntimeException("Reasoning failed: " + e.getMessage(), e);
        }
    }

    private String dispatchTool(ToolExecutionRequest request) {
        String toolName = request.name();
        String argsJson = request.arguments();
        log.info("Agent executing tool: {} with arguments: {}", toolName, argsJson);

        if (kubernetesEvidenceTools == null) {
            return "Error: Kubernetes tools are not available.";
        }

        try {
            JsonNode tree = objectMapper.readTree(argsJson != null && !argsJson.isBlank() ? argsJson : "{}");
            String rawResult = switch (toolName) {
                case "getPodDetails" -> {
                    String ns = tree.path("namespace").asText(null);
                    String pod = tree.path("podName").asText(null);
                    yield kubernetesEvidenceTools.getPodDetails(ns, pod);
                }
                case "getContainerLogs" -> {
                    String ns = tree.path("namespace").asText(null);
                    String pod = tree.path("podName").asText(null);
                    String container = tree.path("containerName").asText(null);
                    Integer tailLines = tree.has("tailLines") && !tree.path("tailLines").isNull() ? tree.path("tailLines").asInt(100) : 100;
                    Boolean previous = tree.has("previous") && !tree.path("previous").isNull() ? tree.path("previous").asBoolean(false) : false;
                    yield kubernetesEvidenceTools.getContainerLogs(ns, pod, container, tailLines, previous);
                }
                case "getEvents" -> {
                    String ns = tree.path("namespace").asText(null);
                    String pod = tree.has("podName") && !tree.path("podName").isNull() && !tree.path("podName").asText().isBlank()
                            ? tree.path("podName").asText() : null;
                    yield kubernetesEvidenceTools.getEvents(ns, pod);
                }
                case "getDeploymentRolloutInfo" -> {
                    String ns = tree.path("namespace").asText(null);
                    String name = tree.has("name") && !tree.path("name").isNull() && !tree.path("name").asText().isBlank()
                            ? tree.path("name").asText()
                            : tree.path("deploymentName").asText(null);
                    yield kubernetesEvidenceTools.getDeploymentRolloutInfo(ns, name);
                }
                default -> "Error: Unknown tool '" + toolName + "'. Available tools: getPodDetails, getContainerLogs, getEvents, getDeploymentRolloutInfo.";
            };
            return sensitiveDataRedactor.redactText(rawResult);
        } catch (Exception e) {
            log.error("Failed to dispatch tool {}: {}", toolName, e.getMessage());
            return "Tool execution error for " + toolName + ": " + e.getMessage();
        }
    }

    private Diagnosis parseResponse(String rawResponse, List<String> toolCallsUsed) {
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
                    List.copyOf(toolCallsUsed),
                    modelName
            );
        } catch (Exception e) {
            log.warn("Failed to parse structured JSON from LLM response: {}. Raw response: {}", e.getMessage(), rawResponse);
            return new Diagnosis(
                    rawResponse,
                    ConfidenceLevel.LOW,
                    "Manual verification recommended due to unstructured model response.",
                    List.copyOf(toolCallsUsed),
                    modelName
            );
        }
    }

    private void logDiagnosis(EvidenceBundle bundle, Diagnosis diagnosis) {
        log.info("DIAGNOSIS PRODUCED for {}/{}:\n  Root Cause: {}\n  Confidence: {}\n  Suggested Fix: {}\n  Tools Used: {}",
                bundle.issue().namespace(), bundle.issue().podName(),
                diagnosis.rootCauseHypothesis(), diagnosis.confidence(), diagnosis.suggestedFix(), diagnosis.toolCallsUsed());
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
