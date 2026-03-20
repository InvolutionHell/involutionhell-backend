package com.involutionhell.backend.openai.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiConfiguration {

    /**
     * 创建用于访问 OpenAI Responses API 的 JDK HTTP 客户端。
     */
    @Bean
    public HttpClient openAiHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }
}
