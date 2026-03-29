package com.involutionhell.backend.openai.service;

import com.involutionhell.backend.openai.config.OpenAiProperties;
import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

@Service
public class HttpOpenAiStreamGateway implements OpenAiStreamGateway {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAiProperties properties;

    /**
     * 创建 OpenAI 流式网关并注入 HTTP 客户端、JSON 工具和配置。
     */
    public HttpOpenAiStreamGateway(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            OpenAiProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 校验 OpenAI 地址、密钥和模型配置是否满足调用要求。
     */
    @Override
    public void validateConfiguration(OpenAiStreamRequest request) {
        if (!StringUtils.hasText(properties.apiUrl())) {
            throw new IllegalStateException("OPENAI_API_URL 未配置");
        }
        if (!StringUtils.hasText(properties.apiKey())) {
            throw new IllegalStateException("OPENAI_API_KEY 未配置");
        }
        if (!StringUtils.hasText(resolveModel(request))) {
            throw new IllegalStateException("OpenAI 模型未配置，请设置 OPENAI_MODEL 或在请求中传入 model");
        }
    }

    /**
     * 以 SSE 模式调用 OpenAI Responses API 并返回原始响应流。
     */
    @Override
    public InputStream openStream(OpenAiStreamRequest request) throws IOException, InterruptedException {
        HttpRequest httpRequest = buildHttpRequest(buildRequestBody(request));
        HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try (InputStream errorStream = response.body()) {
                throw new IllegalStateException(
                        "OpenAI 调用失败: HTTP " + response.statusCode() + " - " + abbreviateErrorBody(errorStream));
            }
        }
        return response.body();
    }

    /**
     * 按照 OpenAI 标准的 /chat/completions 格式构造全套流式请求体。
     * 直接透传完整的 messages 数组，实现真正意义上的上下文多轮理解。
     */
    String buildRequestBody(OpenAiStreamRequest request) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", resolveModel(request));
        requestBody.put("stream", true); // 指定官方进行实时切片下发

        /*
         * ============================
         * 🗑️ 被废弃的畸形伪造格式旧码：
         * requestBody.put("input", List.of(Map.of("role", "user", "content", ... 嵌套 ...
         * )));
         * if (StringUtils.hasText(request.instructions())) {
         * requestBody.put("instructions", request.instructions());
         * }
         * ============================
         */

        requestBody.put("messages", request.messages());

        try {
            return objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }

    /**
     * 构造发往远端（或中转代理）大模型的真实 HTTP 报文。
     */
    HttpRequest buildHttpRequest(String requestBody) {
        String apiUrl = properties.apiUrl();
        // 自动容错：如果环境变量里仅配置了根基地址，自动帮忙拼接聊天补全入口。
        if (!apiUrl.endsWith("/chat/completions")) {
            apiUrl = apiUrl.replaceAll("/+$", "") + "/chat/completions";
        }

        /*
         * ============================
         * 🗑️ 被废弃的基础发包旧路径（因强依赖 properties 没有保护校验）：
         * return HttpRequest.newBuilder(URI.create(properties.apiUrl()))
         * ============================
         */

        return HttpRequest.newBuilder(URI.create(apiUrl))
                // 重点保护区：只有 Java 这里碰到了真实密钥。
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 解析本次请求实际使用的模型名称，请求参数优先于环境默认值。
     */
    String resolveModel(OpenAiStreamRequest request) {
        return StringUtils.hasText(request.model()) ? request.model() : properties.model();
    }

    /**
     * 压缩 OpenAI 错误响应体，避免把过长内容直接返回给调用方。
     */
    private String abbreviateErrorBody(InputStream errorStream) throws IOException {
        String rawBody = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ")
                .trim();
        if (rawBody.length() <= 300) {
            return rawBody;
        }
        return rawBody.substring(0, 300) + "...";
    }
}
