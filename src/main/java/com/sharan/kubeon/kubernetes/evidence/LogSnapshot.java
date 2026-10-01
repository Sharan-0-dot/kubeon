package com.sharan.kubeon.kubernetes.evidence;

public record LogSnapshot(
        String containerName,
        boolean previous,
        String logContent,
        boolean truncated,
        String errorMessage
) {}
