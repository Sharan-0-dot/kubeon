package com.sharan.kubeon;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BadStateReasonTest {

    @Test
    void testFromK8sReasonMatchesKnownReasons() {
        assertThat(BadStateReason.fromK8sReason("OOMKilled"))
                .contains(BadStateReason.OOM_KILLED);
        assertThat(BadStateReason.fromK8sReason("CrashLoopBackOff"))
                .contains(BadStateReason.CRASH_LOOP_BACKOFF);
        assertThat(BadStateReason.fromK8sReason("ImagePullBackOff"))
                .contains(BadStateReason.IMAGE_PULL_BACK_OFF);
        assertThat(BadStateReason.fromK8sReason("ErrImagePull"))
                .contains(BadStateReason.ERR_IMAGE_PULL);
        assertThat(BadStateReason.fromK8sReason("Unhealthy"))
                .contains(BadStateReason.UNHEALTHY_PROBE);
        assertThat(BadStateReason.fromK8sReason("FailedScheduling"))
                .contains(BadStateReason.FAILED_SCHEDULING);
    }

    @Test
    void testFromK8sReasonCaseInsensitive() {
        assertThat(BadStateReason.fromK8sReason("oomkilled"))
                .contains(BadStateReason.OOM_KILLED);
        assertThat(BadStateReason.fromK8sReason("crashloopbackoff"))
                .contains(BadStateReason.CRASH_LOOP_BACKOFF);
    }

    @Test
    void testFromK8sReasonUnknownReturnsEmpty() {
        assertThat(BadStateReason.fromK8sReason("Running")).isEmpty();
        assertThat(BadStateReason.fromK8sReason("")).isEmpty();
        assertThat(BadStateReason.fromK8sReason(null)).isEmpty();
    }
}
