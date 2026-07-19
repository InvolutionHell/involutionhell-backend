package com.involutionhell.backend.usercenter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.involutionhell.backend.common.email.ResendEmailService;
import com.involutionhell.backend.usercenter.model.PendingRegistration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OTP 引擎行为契约：发码/校验/一次性消费/限次/邮箱绑定/过期。
 * 用 mock 的 ResendEmailService 截获邮件正文里的 6 位码来驱动校验。
 */
class RegistrationServiceTests {

    private final ResendEmailService email = mock(ResendEmailService.class);
    private final RegistrationService svc = new RegistrationService(email);

    private PendingRegistration reg(String email) {
        return new PendingRegistration("discord", "snow-1", "Nick", null, email, null);
    }

    /** 触发一次发码并从邮件 HTML 里抓出 6 位验证码。 */
    private String sendAndCaptureCode(String pendingId, String toEmail) {
        when(email.sendHtml(eq(toEmail), anyString(), anyString())).thenReturn(true);
        assertThat(svc.sendOtp(pendingId, toEmail)).isTrue();
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(email, times(1)).sendHtml(eq(toEmail), anyString(), html.capture());
        Matcher m = Pattern.compile("(\\d{6})").matcher(html.getValue());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void verifyWithCorrectCodeConsumesAndReturnsPending() {
        String pid = svc.begin(reg("alice@example.com"));
        String code = sendAndCaptureCode(pid, "alice@example.com");

        Optional<PendingRegistration> ok = svc.verifyAndConsume(pid, "alice@example.com", code);
        assertThat(ok).isPresent();
        assertThat(ok.get().provider()).isEqualTo("discord");

        // 一次性：再用同一码验第二次应失败（已消费）
        assertThat(svc.verifyAndConsume(pid, "alice@example.com", code)).isEmpty();
    }

    @Test
    void wrongCodeFails() {
        String pid = svc.begin(reg("a@e.com"));
        sendAndCaptureCode(pid, "a@e.com");
        assertThat(svc.verifyAndConsume(pid, "a@e.com", "000000")).isEmpty();
    }

    @Test
    void emailMustMatchTheOneCodeWasSentTo() {
        String pid = svc.begin(reg("a@e.com"));
        String code = sendAndCaptureCode(pid, "a@e.com");
        // 换个邮箱来验，即使码对也拒（防换邮箱绕过）
        assertThat(svc.verifyAndConsume(pid, "attacker@e.com", code)).isEmpty();
    }

    @Test
    void tooManyAttemptsInvalidatesChallenge() {
        String pid = svc.begin(reg("a@e.com"));
        String code = sendAndCaptureCode(pid, "a@e.com");
        for (int i = 0; i < 5; i++) {
            svc.verifyAndConsume(pid, "a@e.com", "111111"); // 5 次错码
        }
        // 第 6 次即便码正确也应失败（挑战已作废）
        assertThat(svc.verifyAndConsume(pid, "a@e.com", code)).isEmpty();
    }

    @Test
    void sendOtpFailsWhenNoPending() {
        // 不存在的 pendingId → 不发信
        assertThat(svc.sendOtp("nonexistent", "a@e.com")).isFalse();
    }

    @Test
    void verifyFailsWhenNothingSent() {
        String pid = svc.begin(reg("a@e.com"));
        // 没 sendOtp 就 verify → 无 OTP → 失败
        assertThat(svc.verifyAndConsume(pid, "a@e.com", "123456")).isEmpty();
    }
}
