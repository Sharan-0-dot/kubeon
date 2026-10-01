package com.sharan.kubeon.kubernetes.evidence;

import com.sharan.kubeon.detection.DetectedIssue;

import java.time.Instant;
import java.util.List;

public record EvidenceBundle(
        DetectedIssue issue,
        PodSpecSummary podSpec,
        List<EventSummary> recentEvents,
        List<LogSnapshot> logs,
        Instant collectedAt
) {}
