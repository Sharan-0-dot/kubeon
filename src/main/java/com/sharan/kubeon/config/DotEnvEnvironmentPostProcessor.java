package com.sharan.kubeon.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Order(Ordered.HIGHEST_PRECEDENCE)
public class DotEnvEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path path = Paths.get(".env");
        if (Files.exists(path)) {
            try {
                Map<String, Object> props = new HashMap<>();
                List<String> lines = Files.readAllLines(path);
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                        int idx = trimmed.indexOf('=');
                        String key = trimmed.substring(0, idx).trim();
                        String val = trimmed.substring(idx + 1).trim();
                        if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                            val = val.substring(1, val.length() - 1);
                        }
                        props.put(key, val);
                        if (System.getProperty(key) == null && System.getenv(key) == null) {
                            System.setProperty(key, val);
                        }
                    }
                }
                if (!props.isEmpty()) {
                    environment.getPropertySources().addFirst(new MapPropertySource("dotenvProperties", props));
                }
            } catch (IOException ignored) {
            }
        }
    }
}
