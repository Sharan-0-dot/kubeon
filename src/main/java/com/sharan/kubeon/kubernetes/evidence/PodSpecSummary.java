package com.sharan.kubeon.kubernetes.evidence;

import java.util.List;
import java.util.Map;

public record PodSpecSummary(
        String podName,
        String namespace,
        String nodeName,
        String phase,
        String restartPolicy,
        List<ContainerSummary> containers,
        Map<String, String> labels
) {}
