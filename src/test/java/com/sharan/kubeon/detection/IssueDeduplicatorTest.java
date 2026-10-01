package com.sharan.kubeon.detection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IssueDeduplicatorTest {

    private IssueDeduplicator deduplicator;

    @BeforeEach
    void setUp() {
        deduplicator = new IssueDeduplicator();
    }

    @Test
    void testIsNewReturnsTrueFirstTimeThenFalse() {
        boolean first = deduplicator.isNew("default", "pod-1", BadStateReason.OOM_KILLED);
        boolean second = deduplicator.isNew("default", "pod-1", BadStateReason.OOM_KILLED);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    @Test
    void testDifferentReasonsForSamePodAreBothNew() {
        boolean first = deduplicator.isNew("default", "pod-1", BadStateReason.OOM_KILLED);
        boolean second = deduplicator.isNew("default", "pod-1", BadStateReason.CRASH_LOOP_BACKOFF);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
    }

    @Test
    void testClearRemovesAllReasonsForPod() {
        deduplicator.isNew("default", "pod-1", BadStateReason.OOM_KILLED);
        deduplicator.isNew("default", "pod-1", BadStateReason.CRASH_LOOP_BACKOFF);

        deduplicator.clear("default", "pod-1");

        assertThat(deduplicator.isNew("default", "pod-1", BadStateReason.OOM_KILLED)).isTrue();
        assertThat(deduplicator.isNew("default", "pod-1", BadStateReason.CRASH_LOOP_BACKOFF)).isTrue();
    }
}
