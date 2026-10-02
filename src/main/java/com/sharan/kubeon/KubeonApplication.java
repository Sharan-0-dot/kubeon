package com.sharan.kubeon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@SpringBootApplication
public class KubeonApplication {

	static {
		loadDotEnv();
	}

	public static void main(String[] args) {
		SpringApplication.run(KubeonApplication.class, args);
	}

	private static void loadDotEnv() {
		Path path = Paths.get(".env");
		if (Files.exists(path)) {
			try {
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
						if (System.getProperty(key) == null && System.getenv(key) == null) {
							System.setProperty(key, val);
						}
					}
				}
			} catch (IOException ignored) {
			}
		}
	}

}
