package com.involutionhell.backend.openai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai")
public record OpenAiProperties(String apiUrl, String apiKey, String model) {
}
