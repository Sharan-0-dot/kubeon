package com.sharan.kubeon.reasoning;

import java.util.List;

public record Diagnosis(
        String rootCauseHypothesis,
        ConfidenceLevel confidence,
        String suggestedFix,
        List<String> toolCallsUsed,
        String modelUsed
) {
    public Diagnosis {
        if (toolCallsUsed == null) {
            toolCallsUsed = List.of();
        }
    }
}
