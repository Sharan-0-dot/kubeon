package com.sharan.kubeon.reasoning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfidenceLevelTest {

    @Test
    void testFromStringValidValues() {
        assertThat(ConfidenceLevel.fromString("LOW")).isEqualTo(ConfidenceLevel.LOW);
        assertThat(ConfidenceLevel.fromString("low")).isEqualTo(ConfidenceLevel.LOW);
        assertThat(ConfidenceLevel.fromString("MEDIUM")).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(ConfidenceLevel.fromString("medium")).isEqualTo(ConfidenceLevel.MEDIUM);
        assertThat(ConfidenceLevel.fromString("HIGH")).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(ConfidenceLevel.fromString("high")).isEqualTo(ConfidenceLevel.HIGH);
    }

    @Test
    void testFromStringInvalidOrEmptyDefaultsToLow() {
        assertThat(ConfidenceLevel.fromString("")).isEqualTo(ConfidenceLevel.LOW);
        assertThat(ConfidenceLevel.fromString("   ")).isEqualTo(ConfidenceLevel.LOW);
        assertThat(ConfidenceLevel.fromString(null)).isEqualTo(ConfidenceLevel.LOW);
        assertThat(ConfidenceLevel.fromString("UNKNOWN_VALUE")).isEqualTo(ConfidenceLevel.LOW);
    }
}
