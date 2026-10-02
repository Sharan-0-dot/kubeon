package com.sharan.kubeon.api.dto;

import com.sharan.kubeon.detection.DetectedIssue;

import java.util.List;

public record HealthCheckResult(
        String status,
        int checkedPodCount,
        int issueCount,
        List<DetectedIssue> issues
) {}
