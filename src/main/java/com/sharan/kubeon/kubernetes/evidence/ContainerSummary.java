package com.sharan.kubeon.kubernetes.evidence;

import java.util.Map;

public record ContainerSummary(
        String name,
        String image,
        Map<String, String> requests,
        Map<String, String> limits,
        boolean hasLivenessProbe,
        boolean hasReadinessProbe,
        boolean hasStartupProbe,
        boolean isInit
) {}
