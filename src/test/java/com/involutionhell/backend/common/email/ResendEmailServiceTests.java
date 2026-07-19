package com.involutionhell.backend.common.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * ResendEmailService 离线可测部分：请求体构造 + 未配置时不发信。
 * 真实发信需线上 key（已在集成外冒烟：curl → Resend 返回 email id）。
 */
class ResendEmailServiceTests {

    @Test
    void buildsResendPayload() {
        String payload = ResendEmailService.buildPayload(
                "noreply@involutionhell.com", "u@example.com", "验证码", "<p>123456</p>");
        JSONObject j = JSONObject.parseObject(payload);
        assertThat(j.getString("from")).isEqualTo("noreply@involutionhell.com");
        assertThat(j.getString("to")).isEqualTo("u@example.com");
        assertThat(j.getString("subject")).isEqualTo("验证码");
        assertThat(j.getString("html")).isEqualTo("<p>123456</p>");
    }

    @Test
    void notConfiguredWhenKeyBlankAndSendReturnsFalse() {
        ResendEmailService svc = new ResendEmailService("", "onboarding@resend.dev");
        assertThat(svc.isConfigured()).isFalse();
        // 未配置时 sendHtml 直接返回 false，不发网络
        assertThat(svc.sendHtml("u@example.com", "s", "<p>h</p>")).isFalse();
    }

    @Test
    void configuredWhenKeyPresent() {
        assertThat(new ResendEmailService("re_xxx", "onboarding@resend.dev").isConfigured()).isTrue();
    }
}
