package com.sharan.kubeon.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class GeminiConfig {

    private static final Logger log = LoggerFactory.getLogger(GeminiConfig.class);

    @Value("${kubeon.gemini.api-key:${GEMINI_API_KEY:}}")
    private String apiKey;

    @Value("${kubeon.gemini.model:${GEMINI_MODEL:gemini-2.5-flash}}")
    private String modelName;

    @Value("${kubeon.gemini.temperature:0.1}")
    private double temperature;

    @Value("${kubeon.gemini.timeout-seconds:30}")
    private int timeoutSeconds;

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${kubeon.gemini.api-key:${GEMINI_API_KEY:}}') && !'${kubeon.gemini.api-key:${GEMINI_API_KEY:}}'.equals('your_gemini_api_key_here')")
    public ChatModel geminiChatModel() {
        log.info("Configuring GoogleAiGeminiChatModel with model: {}", modelName);
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey.trim())
                .modelName(modelName.trim())
                .temperature(temperature)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
