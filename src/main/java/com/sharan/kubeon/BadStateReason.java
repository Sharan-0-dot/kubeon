package com.sharan.kubeon;

import java.util.Arrays;
import java.util.Optional;

public enum BadStateReason {
    OOM_KILLED("OOMKilled"),
    CRASH_LOOP_BACKOFF("CrashLoopBackOff"),
    IMAGE_PULL_BACK_OFF("ImagePullBackOff"),
    ERR_IMAGE_PULL("ErrImagePull");

    private final String k8sReason;

    BadStateReason(String k8sReason) { this.k8sReason = k8sReason; }

    public static Optional<BadStateReason> fromK8sReason(String reason) {
        return Arrays.stream(values())
                .filter(r -> r.k8sReason.equals(reason))
                .findFirst();
    }
}
