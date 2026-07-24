package com.involutionhell.backend.common.email;

import com.alibaba.fastjson.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 通过 Resend REST API 发事务邮件（OTP 等）。后端直连 HTTPS API（send-only key），
 * 不走 MCP（那是给 agent 交互用的）、也不自建 SMTP（Oracle 封了出站 25，见 AGENTS.md）。
 *
 * 发件域名未在 Resend 验证前，只能从 onboarding@resend.dev 发给账号本人；
 * 验证 involutionhell.com 后把 resend.from 换成 noreply@involutionhell.com。
 *
 * ponytail: 同步阻塞发信（15s 超时）跑在调用线程。注册是低频交互、用户本就在等
 * "验证码已发送"，同步可接受；真到高 QPS 再挪到异步线程池，别为现在的量提前引入。
 */
@Service
public class ResendEmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);
    private static final String ENDPOINT = "https://api.resend.com/emails";
    // Resend 的共享测试发件地址：只能投递给 Resend 账号本人邮箱，发给真实用户会被拒。
    static final String SANDBOX_FROM = "onboarding@resend.dev";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final String apiKey;
    private final String from;

    public ResendEmailService(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from:onboarding@resend.dev}") String from) {
        this.apiKey = apiKey;
        this.from = from;
        // 配了 key 却仍用沙箱发件地址 = 只能发给账号本人，给真实注册者一律被拒。
        // 启动即告警，避免"key 有效但 OTP 发不出"的隐性陷阱（review #4）。
        if (isConfigured() && SANDBOX_FROM.equalsIgnoreCase(from)) {
            log.warn("resend.from 仍是沙箱地址 {}：只能发给 Resend 账号本人。"
                    + "给真实用户发信前，请验证发信域名并把 RESEND_FROM 改为 noreply@你的域名", SANDBOX_FROM);
        }
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 发一封 HTML 邮件。返回是否成功；失败只记日志不抛（调用方决定是否阻断，
     * OTP 场景应把"发信失败"作为可重试的用户可见错误，而非 500）。
     * 日志不落收件邮箱（PII）与 Resend 响应体（可能回显邮箱），只记状态码/异常类型。
     */
    public boolean sendHtml(String to, String subject, String html) {
        if (!isConfigured()) {
            log.warn("Resend 未配置（缺 resend.api-key），跳过发信");
            return false;
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildPayload(from, to, subject, html)))
                .build();
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                return true;
            }
            // 不记 body：Resend 错误响应常回显收件邮箱等 PII。
            log.warn("Resend 发信失败 status={}", resp.statusCode());
            return false;
        } catch (InterruptedException e) {
            // 恢复中断标志，别把中断吞掉
            Thread.currentThread().interrupt();
            log.warn("Resend 发信被中断");
            return false;
        } catch (Exception e) {
            // 只记异常类型，不记 message（可能含收件邮箱）
            log.warn("Resend 发信异常: {}", e.getClass().getSimpleName());
            return false;
        }
    }

    /** 构造 Resend 请求体。生产发信路径与单测断言路径共用同一实现（review #11）。 */
    static String buildPayload(String from, String to, String subject, String html) {
        JSONObject body = new JSONObject();
        body.put("from", from);
        body.put("to", to);
        body.put("subject", subject);
        body.put("html", html);
        return body.toJSONString();
    }
}
