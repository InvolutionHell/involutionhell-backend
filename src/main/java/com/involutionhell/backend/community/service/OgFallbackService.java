package com.involutionhell.backend.community.service;

import com.involutionhell.backend.openai.config.OpenAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OG 抓取失败时的 LLM 兜底：用 LLM 根据 URL 本身（host + path）猜个 title/description。
 *
 * 何时触发：{@link OgFetchService} 返回的 errorMessage 非 null（PDF / 反爬 / 限流 等）。
 * 不能保证准确，但能避免 feed 卡片完全空白 ——
 * 用户看到 "&lt;arxiv 论文 id&gt; — 摘要：暂不可用" 也比一行 URL 强。
 *
 * 复用 {@link OpenAiProperties}（model/apiUrl/apiKey），跟 ClassificationService 同一套 LLM。
 * 失败降级：返回空结果（title/description 都 null），调用方自己决定显示策略。
 */
@Service
public class OgFallbackService {

    private static final Logger log = LoggerFactory.getLogger(OgFallbackService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * Prompt 设计原则：
     * - 强约束只返回 JSON，禁止 markdown / 解释，跟 ClassificationService 一致
     * - 明确"不能编造内容"——只根据 URL 本身可推断的信息给标题
     * - 失败标识：所有字段为空时返回 {"title":null,"description":null}
     */
    private static final String SYSTEM_PROMPT = """
            你是一个网页元数据猜测助手。用户会给你一条无法直接抓取 OG meta 的 URL，
            请根据 URL 的 host、path、query 推断出最可能的标题和一句简短描述。

            约束：
            - 只能基于 URL 本身可推断的事实，**不要编造文章内容**
            - 标题尽量贴近真实页面标题的风格（如 arxiv 论文用 "[Paper] <id>"，
              微信公众号用 "<公众号> · <推断主题>"，github repo 用 "<owner>/<repo>" 等）
            - 描述一句话说明这是什么类型的资源（论文、技术博客、新闻报道、代码仓库、视频、PDF 文档...）
            - 中文输出
            - 完全无法推断时返回 {"title":null,"description":null}

            严格只返回 JSON，不要任何解释、代码块标记（不要 ```json）或其他文字：
            {"title":"<推断标题>", "description":"<一句话描述>"}
            """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAiProperties properties;

    public OgFallbackService(HttpClient httpClient,
                             ObjectMapper objectMapper,
                             OpenAiProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 给 URL 猜个 OG。失败返回 (null, null)，让调用方决定是否回填。
     */
    public Guess guess(String url, String host) {
        if (url == null || url.isBlank()) {
            return Guess.empty();
        }
        // 没配 apiKey 时直接降级，避免发空请求被 LLM 服务拒绝
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            log.debug("og-fallback 跳过：未配置 OPENAI_API_KEY");
            return Guess.empty();
        }
        try {
            String userContent = "URL: " + url + "\nHost: " + (host == null ? "(unknown)" : host);
            String requestBody = buildRequestBody(SYSTEM_PROMPT, userContent);
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.apiUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("og-fallback LLM 失败（HTTP 非 2xx）: host={} status={}", host, response.statusCode());
                return Guess.empty();
            }
            return parseLlmResponse(response.body(), host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Guess.empty();
        } catch (Exception e) {
            log.warn("og-fallback 异常: host={} error={}", host, e.getMessage());
            return Guess.empty();
        }
    }

    private String buildRequestBody(String systemContent, String userContent) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.model());
            body.put("temperature", 0);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemContent),
                    Map.of("role", "user", "content", userContent)
            ));
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            // ObjectMapper 写 LinkedHashMap 不会抛 checked exception，但兜底以防万一
            throw new IllegalStateException("og-fallback 构造请求体失败", e);
        }
    }

    private Guess parseLlmResponse(String body, String host) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asString(null);
            if (content == null || content.isBlank()) return Guess.empty();
            // LLM 偶尔会带 ```json ... ``` 包装，剥一下
            String stripped = content.trim();
            if (stripped.startsWith("```")) {
                int firstNewline = stripped.indexOf('\n');
                if (firstNewline > 0) stripped = stripped.substring(firstNewline + 1);
                if (stripped.endsWith("```")) stripped = stripped.substring(0, stripped.length() - 3);
                stripped = stripped.trim();
            }
            JsonNode payload = objectMapper.readTree(stripped);
            String title = nullableText(payload.path("title"));
            String description = nullableText(payload.path("description"));
            if (title == null && description == null) {
                return Guess.empty();
            }
            return new Guess(title, description);
        } catch (Exception e) {
            log.warn("og-fallback LLM 响应解析失败: host={} error={}", host, e.getMessage());
            return Guess.empty();
        }
    }

    private static String nullableText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String s = node.asString(null);
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * 兜底猜测结果。两个字段都为 null 表示无可用猜测。
     */
    public record Guess(String title, String description) {
        public static Guess empty() { return new Guess(null, null); }
        public boolean isEmpty() { return title == null && description == null; }
    }
}
