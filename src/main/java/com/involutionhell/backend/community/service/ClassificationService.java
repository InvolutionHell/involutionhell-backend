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
            你是一个面向中国大陆互联网社群（involutionhell.com）的内容审核 AI。
            运营地在中国，必须遵守《网络安全法》《网络信息内容生态治理规定》等
            中国大陆现行法律法规。根据输入信息，把链接分到以下分类之一：
            %s

            同时判断内容是否存在 4 类安全问题。对 nsfw/ad/flame 采用"宁松勿严"
            策略（社群正常技术分享放行）；对 illegal 必须严格，宁可误报。

            - nsfw: 色情、裸露、血腥暴力、猎奇不适。仅当**明确**涉及时为 true。
            - ad:   **仅**标注"纯推销且无实质内容价值"的页面，要求同时满足：
                    (a) 主体是商品 / 课程 / 服务 / 会员的购买引导，且
                    (b) 几乎没有独立技术 / 知识 / 新闻价值。
                    典型例子：带"立即购买 / 限时优惠 / 扫码报名"的纯卖课软文、
                    电商商品页、付费社群 / 会员订阅落地页。
                    **反例（全部 false）**：产品发布公告、版本更新说明（哪怕
                    带"立即体验"按钮语气）、技术博客、论文宣传、开源项目介绍、
                    新闻报道、个人作品集。
            - flame: 明显引战 / 人身攻击 / 极端言论 / 刻意煽动对立。技术路线之争、
                    理性观点分歧**不算**。
            - illegal: 疑似违反中国大陆法律法规的内容。任一命中即 true：
                    · 反对宪法基本原则、颠覆国家政权、煽动分裂国家、破坏国家统一
                    · 攻击党和政府、宣扬港独 / 台独 / 藏独 / 疆独
                    · 煽动民族歧视 / 民族仇恨、破坏民族团结
                    · 宣扬邪教、封建迷信（法轮功等）
                    · 散布谣言、扰乱社会秩序、破坏社会稳定
                    · 宣扬 / 教唆赌博 / 毒品 / 淫秽色情 / 暴力恐怖
                    · 泄露国家秘密、危害国家安全或利益
                    · 翻墙工具 / VPN 售卖 / 违禁品交易
                    · 明显违反《网络安全法》《治安管理处罚法》《刑法》其他情形
                    技术讨论涉及敏感话题但论点中立且学术讨论 **不算** illegal。

            严格只返回 JSON，不要任何解释、代码块标记（不要 ```json）或其他文字：
            {"category": "<slug>", "nsfw": false, "ad": false, "flame": false, "illegal": false}
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

            // 上游 provider content filter 触发（智谱：error.code=1301 / contentFilter 非空）。
            // 这种拒绝本身是强信号：说明 provider 自己都觉得内容敏感。直接打 illegal=true
            // 走 FLAGGED，不要 fallback 成全 false 放行——那样是 security gap。
            JsonNode errorNode = root.path("error");
            boolean hasContentFilterFlag = root.has("contentFilter")
                    && !root.path("contentFilter").isEmpty()
                    && !root.path("contentFilter").isNull();
            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                String code = errorNode.path("code").asText("");
                String message = errorNode.path("message").asText("");
                if ("1301".equals(code) || hasContentFilterFlag) {
                    log.warn("classification 被 provider content filter 拦截，标 illegal: host={} code={} msg={}",
                            host, code, message);
                    return ClassificationResult.blockedByContentFilter();
                }
                // 其它 error（限流 / 配置错误 / 网络等）走普通 fallback
                log.warn("classification 上游返回 error: host={} code={} msg={}", host, code, message);
                return ClassificationResult.fallback();
            }
            if (hasContentFilterFlag) {
                log.warn("classification 被 content filter 拦截（无 error 字段）: host={}", host);
                return ClassificationResult.blockedByContentFilter();
            }

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
            boolean nsfw    = result.path("nsfw").asBoolean(false);
            boolean ad      = result.path("ad").asBoolean(false);
            boolean flame   = result.path("flame").asBoolean(false);
            // 旧模型可能不返回 illegal 字段，缺失时按 false 降级（不阻拦），
            // 命中 nsfw/ad/flame 任一时已经会走 FLAGGED
            boolean illegal = result.path("illegal").asBoolean(false);

            // normalize 兜底：非法 slug 转 other
            String category = LinkCategory.normalize(rawCategory);
            if (!category.equals(rawCategory)) {
                log.warn("classification 返回非法分类，降级为 other: host={} raw={}", host, rawCategory);
            }

            log.debug("classification 完成: host={} category={} nsfw={} ad={} flame={} illegal={}",
                    host, category, nsfw, ad, flame, illegal);
            return new ClassificationResult(category, nsfw, ad, flame, illegal);

        } catch (Exception e) {
            log.warn("classification 响应解析失败，降级: host={} error={}", host, e.getMessage());
            return ClassificationResult.fallback();
        }
    }
}
