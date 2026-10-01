package com.sharan.kubeon.kubernetes.evidence;

public record EventSummary(
        String type,
        String reason,
        String message,
        Integer count,
        String firstTimestamp,
        String lastTimestamp,
        String sourceComponent
) {}
