package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.model.LinkCategory;
import com.involutionhell.backend.openai.config.OpenAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
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
 * DeepSeek 分类服务（M3）。
 *
 * 职责：根据链接的 ogTitle + ogDescription + host，调 DeepSeek API
 * 返回结构化分类结果 {@link ClassificationResult}。
 *
 * 设计决策：
 * - 独立 Service，不复用 OpenAIController 的 chat 路由，避免 prompt 耦合
 * - 复用 {@link OpenAiProperties} 的 apiUrl / apiKey（DeepSeek 兼容 /chat/completions 接口）
 * - 复用 openai 模块的 JDK HttpClient Bean
 * - temperature=0，强约束只返回 JSON，降低幻觉概率
 * - 失败降级：不抛异常，返回 ClassificationResult.fallback()，记 warn log
 *
 * Prompt 格式约束：只返回 JSON，不附加任何解释文字，防止解析失败。
 */
@Service
public class ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(ClassificationService.class);

    /** DeepSeek 调用超时（非流式，等待完整 JSON 响应）。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * 分类 prompt 模板。
     * 变量：{CATEGORIES}、{TITLE}、{DESCRIPTION}、{HOST}
     * 强约束：只返回 JSON，不带任何 markdown 代码块或解释。
     */
    private static final String SYSTEM_PROMPT = """
            你是一个内容分类 AI。根据输入信息，将链接分到以下分类之一：
            %s

            同时判断内容是否存在安全问题。判定采用"宁松勿严"策略——社群用户的正常
            分享（技术公告 / 产品更新 / 研究进展等）即使带一点营销语气也应放行，
            只有明显的纯商业推广才应标 ad。

            - nsfw: 色情、裸露、暴力血腥、猎奇不适内容。仅当内容**明确**涉及时为 true
            - ad:   **仅**标注"纯推销且无实质内容价值"的页面，命中要求同时满足：
                    (a) 主体是商品 / 课程 / 服务 / 会员的购买引导，且
                    (b) 几乎没有独立技术 / 知识 / 新闻价值
                    典型例子：带"立即购买 / 限时优惠 / 扫码报名"的纯卖课软文、
                    电商商品页、付费社群 / 会员订阅落地页。
                    **反例（全部 false）**：产品发布公告、版本更新说明（哪怕
                    带"立即体验"按钮语气）、技术博客、论文宣传、开源项目介绍、
                    新闻报道、个人作品集。
            - flame: 明显引战 / 人身攻击 / 极端言论 / 刻意煽动对立。技术路线之争、
                    理性观点分歧**不算**。

            严格只返回 JSON，不要任何解释、代码块标记（不要 ```json）或其他文字：
            {"category": "<slug>", "nsfw": false, "ad": false, "flame": false}
            """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OpenAiProperties properties;

    public ClassificationService(HttpClient httpClient,
                                 ObjectMapper objectMapper,
                                 OpenAiProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 对链接内容进行分类和安全判定。
     *
     * @param ogTitle       OG 标题（可 null，OG 抓取失败时为空）
     * @param ogDescription OG 描述（可 null）
     * @param host          规范化后的根域（如 mp.weixin.qq.com）
     * @return 分类结果；失败时返回降级结果（category=other, flags 全 false）
     */
    public ClassificationResult classify(String ogTitle, String ogDescription, String host) {
        log.debug("classification 开始: host={} title={}", host, ogTitle);
        try {
            String systemContent = buildSystemPrompt();
            String userContent = buildUserContent(ogTitle, ogDescription, host);

            String requestBody = buildRequestBody(systemContent, userContent);
            HttpRequest request = buildHttpRequest(requestBody);

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("classification 失败（HTTP 非 2xx）: host={} status={}", host, response.statusCode());
                return ClassificationResult.fallback();
            }

            return parseResponse(response.body(), host);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("classification 被中断: host={}", host);
            return ClassificationResult.fallback();
        } catch (Exception e) {
            log.warn("classification 异常，降级为 other: host={} error={}", host, e.getMessage());
            return ClassificationResult.fallback();
        }
    }

    /**
     * 构造 DeepSeek system prompt，内嵌所有合法分类 slug。
     */
    String buildSystemPrompt() {
        String categories = String.join(", ", LinkCategory.ALL);
        return SYSTEM_PROMPT.formatted(categories);
    }

    /**
     * 构造用户输入内容，缺失字段用"（无）"占位，避免 AI 看到空字符串产生歧义。
     */
    String buildUserContent(String ogTitle, String ogDescription, String host) {
        return """
                标题：%s
                描述：%s
                来源域名：%s
                """.formatted(
                ogTitle != null ? ogTitle : "（无）",
                ogDescription != null ? ogDescription : "（无）",
                host != null ? host : "（无）"
        );
    }

    /**
     * 构造符合 /chat/completions 格式的请求体 JSON。
     * temperature=0 确保结果稳定可解析。
     */
    String buildRequestBody(String systemContent, String userContent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("temperature", 0);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemContent),
                Map.of("role", "user", "content", userContent)
        ));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("请求序列化失败", e);
        }
    }

    /**
     * 构造 HTTP 请求，复用 openai.api-url / openai.api-key。
     * DeepSeek 兼容 OpenAI 的 /chat/completions 接口，无需额外配置。
     */
    HttpRequest buildHttpRequest(String requestBody) {
        String apiUrl = properties.apiUrl();
        if (!apiUrl.endsWith("/chat/completions")) {
            apiUrl = apiUrl.replaceAll("/+$", "") + "/chat/completions";
        }
        return HttpRequest.newBuilder(URI.create(apiUrl))
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * 解析 DeepSeek 返回的 JSON 响应，提取 content 字段并转换为 ClassificationResult。
     *
     * 容错：
     * - content 被 markdown 代码块包裹时自动剥离 ```json ... ```
     * - category 非法时 LinkCategory.normalize() 兜底为 other
     * - 缺失 flag 字段默认 false
     */
    ClassificationResult parseResponse(String responseBody, String host) {
        try {
            // 使用 readValue(byte[], type) 避免 readTree(String) 的 deprecation 警告
            JsonNode root = objectMapper.readValue(
                    responseBody.getBytes(StandardCharsets.UTF_8), JsonNode.class);
            // choices[0].message.content
            String content = root.path("choices").path(0)
                    .path("message").path("content").asText();

            if (content.isBlank()) {
                log.warn("classification 返回空 content: host={}", host);
                return ClassificationResult.fallback();
            }

            // 剥离可能存在的 markdown 代码块
            content = content.trim();
            if (content.startsWith("```")) {
                content = content.replaceAll("^```[a-z]*\\s*", "").replaceAll("```\\s*$", "").trim();
            }

            JsonNode result = objectMapper.readValue(
                    content.getBytes(StandardCharsets.UTF_8), JsonNode.class);
            String rawCategory = result.path("category").asText("other");
            boolean nsfw  = result.path("nsfw").asBoolean(false);
            boolean ad    = result.path("ad").asBoolean(false);
            boolean flame = result.path("flame").asBoolean(false);

            // normalize 兜底：非法 slug 转 other
            String category = LinkCategory.normalize(rawCategory);
            if (!category.equals(rawCategory)) {
                log.warn("classification 返回非法分类，降级为 other: host={} raw={}", host, rawCategory);
            }

            log.debug("classification 完成: host={} category={} nsfw={} ad={} flame={}",
                    host, category, nsfw, ad, flame);
            return new ClassificationResult(category, nsfw, ad, flame);

        } catch (Exception e) {
            log.warn("classification 响应解析失败，降级: host={} error={}", host, e.getMessage());
            return ClassificationResult.fallback();
        }
    }
}
