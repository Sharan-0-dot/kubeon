package com.sharan.kubeon.detection;

import java.time.Instant;

public record DetectedIssue(
        String namespace,
        String podName,
        BadStateReason reason,
        Instant detectedAt,
        String sourceMessage
) {}