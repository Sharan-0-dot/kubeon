package com.sharan.kubeon.detection;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum BadStateReason {
    OOM_KILLED("OOMKilled"),
    CRASH_LOOP_BACKOFF("CrashLoopBackOff"),
    IMAGE_PULL_BACK_OFF("ImagePullBackOff"),
    ERR_IMAGE_PULL("ErrImagePull"),
    UNHEALTHY_PROBE("Unhealthy"),
    FAILED_SCHEDULING("FailedScheduling");

    private final List<String> k8sReasons;

    BadStateReason(String... k8sReasons) {
        this.k8sReasons = Arrays.asList(k8sReasons);
    }

    public static Optional<BadStateReason> fromK8sReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(r -> r.k8sReasons.stream().anyMatch(reason::equalsIgnoreCase))
                .findFirst();
    }
}
