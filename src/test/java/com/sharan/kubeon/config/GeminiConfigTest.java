package com.sharan.kubeon.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class GeminiConfigTest {

    @Test
    void testGeminiConfigDefaults() {
        GeminiConfig config = new GeminiConfig();
        ReflectionTestUtils.setField(config, "apiKey", "");
        ReflectionTestUtils.setField(config, "modelName", "gemini-2.0-flash");
        ReflectionTestUtils.setField(config, "temperature", 0.1);
        ReflectionTestUtils.setField(config, "timeoutSeconds", 30);

        assertThat(config.getModelName()).isEqualTo("gemini-2.0-flash");
        assertThat(config.getTemperature()).isEqualTo(0.1);
        assertThat(config.getTimeoutSeconds()).isEqualTo(30);
    }
}
