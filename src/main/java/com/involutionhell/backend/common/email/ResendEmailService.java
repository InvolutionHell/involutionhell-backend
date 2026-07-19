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
 */
@Service
public class ResendEmailService {

    private static final Logger log = LoggerFactory.getLogger(ResendEmailService.class);
    private static final String ENDPOINT = "https://api.resend.com/emails";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final String apiKey;
    private final String from;

    public ResendEmailService(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from:onboarding@resend.dev}") String from) {
        this.apiKey = apiKey;
        this.from = from;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 发一封 HTML 邮件。返回是否成功；失败只记日志不抛（调用方决定是否阻断，
     * OTP 场景应把"发信失败"作为可重试的用户可见错误，而非 500）。
     */
    public boolean sendHtml(String to, String subject, String html) {
        if (!isConfigured()) {
            log.warn("Resend 未配置（缺 resend.api-key），跳过发信 to={}", to);
            return false;
        }
        JSONObject body = new JSONObject();
        body.put("from", from);
        body.put("to", to);
        body.put("subject", subject);
        body.put("html", html);
        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .build();
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 == 2) {
                return true;
            }
            log.warn("Resend 发信失败 status={} body={}", resp.statusCode(), resp.body());
            return false;
        } catch (Exception e) {
            log.warn("Resend 发信异常 to={}: {}", to, e.getMessage());
            return false;
        }
    }

    /** 供单测断言请求体，不发网络。 */
    static String buildPayload(String from, String to, String subject, String html) {
        JSONObject body = new JSONObject();
        body.put("from", from);
        body.put("to", to);
        body.put("subject", subject);
        body.put("html", html);
        return body.toJSONString();
    }
}
