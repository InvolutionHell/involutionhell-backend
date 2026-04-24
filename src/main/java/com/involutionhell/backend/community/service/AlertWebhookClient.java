package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.model.SharedLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实时告警 webhook。
 *
 * 当 enrichment worker 判定 status=FLAGGED 时，fire-and-forget 给管理员推送渠道
 * （当前是 ChatBot 的 aiohttp alert 接收器）发一条 POST，让管理员即时收到 Discord/邮件 alert。
 *
 * 为什么不是"每日 digest"：FLAGGED 是 AI 认为有 nsfw/ad/flame/illegal 内容，
 * 不能放任它在队列里等第二天。
 *
 * 容错：网络/HTTP 失败完全静默，绝不影响 enrichment 主流程。
 * 线程模型：@Async 让调用方立即返回，内部用 HttpClient#sendAsync 不阻塞 @Async 线程池，
 * 避免 FLAGGED 高峰期 webhook 卡死 enrichment worker 线程池。
 */
@Component
public class AlertWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookClient.class);

    private final String webhookUrl;
    private final String internalKey;
    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public AlertWebhookClient(
            @Value("${community.alert.webhook-url:}") String webhookUrl,
            @Value("${internal.api-key:}") String internalKey,
            ObjectMapper objectMapper) {
        this.webhookUrl = webhookUrl;
        this.internalKey = internalKey;
        this.objectMapper = objectMapper;
        // 短超时，不让失败的 webhook 把 @Async 线程池堵住
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /**
     * 发送 FLAGGED 告警。fire-and-forget：调用方无需 await，HTTP 走 sendAsync 不占线程。
     *
     * @param link   被判定 FLAGGED 的链接
     * @param flags  nsfw/ad/flame/illegal 四项布尔
     */
    @Async
    public void notifyFlagged(SharedLink link, Map<String, Boolean> flags) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;  // 未配置就静默跳过
        }

        // 用 ObjectMapper 序列化，避免手写 JSON 转义漏处理 UTF-16 代理对 / 控制字符等边界
        String body;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "flagged");
            payload.put("id", link.id());
            payload.put("url", link.url());
            payload.put("host", link.host());
            payload.put("title", link.ogTitle());
            payload.put("recommendation", link.recommendation());
            payload.put("flags", flags);
            body = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("alert webhook 序列化失败（忽略）: linkId={} error={}", link.id(), e.getMessage());
            return;
        }

        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Key", internalKey == null ? "" : internalKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
        } catch (Exception e) {
            log.warn("alert webhook 构造请求失败（忽略）: linkId={} error={}", link.id(), e.getMessage());
            return;
        }

        // sendAsync：调用立即返回，不阻塞 @Async 线程池；结果回调里只打日志
        Long linkId = link.id();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .whenComplete((resp, error) -> {
                    if (error != null) {
                        log.warn("alert webhook 失败（忽略）: linkId={} error={}", linkId, error.getMessage());
                        return;
                    }
                    if (resp.statusCode() >= 400) {
                        log.warn("alert webhook 非 2xx: status={} body={}", resp.statusCode(), resp.body());
                    } else {
                        log.info("alert webhook OK: linkId={} status={}", linkId, resp.statusCode());
                    }
                });
    }
}
