package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.model.SharedLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 实时告警 webhook。
 *
 * 当 enrichment worker 判定 status=FLAGGED 时，fire-and-forget 给管理员推送渠道
 * （当前是 ChatBot 的 aiohttp alert 接收器）发一条 POST，让管理员即时收到 Discord/邮件 alert。
 *
 * 为什么不是"每日 digest"：FLAGGED 是 AI 认为有 nsfw/ad/flame 内容，不能放任它在
 * 队列里等第二天。PENDING_MANUAL（白名单外但 AI 无 flag）走 digest 即可。
 *
 * 容错：网络/HTTP 失败完全静默，绝不影响 enrichment 主流程。@Async 让调用立即返回。
 */
@Component
public class AlertWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(AlertWebhookClient.class);

    private final String webhookUrl;
    private final String internalKey;
    private final HttpClient http;

    public AlertWebhookClient(
            @Value("${community.alert.webhook-url:}") String webhookUrl,
            @Value("${internal.api-key:}") String internalKey) {
        this.webhookUrl = webhookUrl;
        this.internalKey = internalKey;
        // 短超时，不让失败的 webhook 把 @Async 线程池堵住
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /**
     * 发送 FLAGGED 告警。fire-and-forget：调用方无需 await。
     *
     * @param link   被判定 FLAGGED 的链接
     * @param flags  nsfw/ad/flame 三项布尔
     */
    @Async
    public void notifyFlagged(SharedLink link, Map<String, Boolean> flags) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;  // 未配置就静默跳过
        }

        String body = String.format(
                "{\"type\":\"flagged\",\"id\":%d,\"url\":%s,\"host\":%s,\"title\":%s,"
                        + "\"recommendation\":%s,\"flags\":{\"nsfw\":%b,\"ad\":%b,\"flame\":%b}}",
                link.id(),
                quote(link.url()),
                quote(link.host()),
                quote(link.ogTitle()),
                quote(link.recommendation()),
                Boolean.TRUE.equals(flags.get("nsfw")),
                Boolean.TRUE.equals(flags.get("ad")),
                Boolean.TRUE.equals(flags.get("flame"))
        );

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Key", internalKey == null ? "" : internalKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("alert webhook 非 2xx: status={} body={}", resp.statusCode(), resp.body());
            } else {
                log.info("alert webhook OK: linkId={} status={}", link.id(), resp.statusCode());
            }
        } catch (Exception e) {
            // 彻底吞掉：webhook 失败不能影响 enrichment 结果
            log.warn("alert webhook 失败（忽略）: linkId={} error={}", link.id(), e.getMessage());
        }
    }

    /** 简易 JSON 字符串转义，避免引入 Jackson 依赖在这个轻量 client 里。 */
    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
