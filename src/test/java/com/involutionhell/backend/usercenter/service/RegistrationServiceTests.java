package com.involutionhell.backend.usercenter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import com.involutionhell.backend.common.email.ResendEmailService;
import com.involutionhell.backend.usercenter.model.PendingRegistration;
import com.involutionhell.backend.usercenter.service.RegistrationService.SendResult;
import com.involutionhell.backend.usercenter.service.RegistrationService.VerifiedRegistration;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * OTP 引擎行为契约（review 后重做）：一次性/累积限次/重发限流/冷却/过期/邮箱绑定/
 * 返回已验证邮箱/并发消费。用 mock 的 ResendEmailService 抓邮件正文里的码，用
 * FakeTicker 控制时间。
 */
class RegistrationServiceTests {

    /** 可控时钟，驱动 OTP 过期与会话 TTL / 冷却。 */
    private static final class FakeTicker implements Ticker {
        private long nanos = 0;
        @Override public long read() { return nanos; }
        void advance(Duration d) { nanos += d.toNanos(); }
    }

    private final ResendEmailService email = mock(ResendEmailService.class);
    private final FakeTicker ticker = new FakeTicker();
    private final RegistrationService svc = new RegistrationService(email, ticker, false);

    // 默认按"已配置 Resend"跑正常发信路径；dev-fallback 用例单独覆盖为 false。
    @BeforeEach
    void resendConfigured() {
        when(email.isConfigured()).thenReturn(true);
    }

    private PendingRegistration reg(String providerEmail) {
        return new PendingRegistration("discord", "snow-1", "Nick", null, providerEmail, null);
    }

    /** 发一次码并抓出邮件正文里最新的 6 位验证码（sendHtml 已 mock 成 true）。 */
    private String sendAndCaptureCode(String pendingId, String toEmail) {
        when(email.sendHtml(eq(toEmail), anyString(), anyString())).thenReturn(true);
        assertThat(svc.sendOtp(pendingId, toEmail)).isEqualTo(SendResult.SENT);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(email, org.mockito.Mockito.atLeastOnce())
                .sendHtml(eq(toEmail), anyString(), html.capture());
        Matcher m = Pattern.compile("(\\d{6})").matcher(html.getAllValues().get(html.getAllValues().size() - 1));
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    @Test
    void correctCodeConsumesAndReturnsVerifiedEmail() {
        String pid = svc.begin(reg("provider-prefill@example.com"));
        // 用户在验证步改成自己另一个邮箱
        String code = sendAndCaptureCode(pid, "real@example.com");

        Optional<VerifiedRegistration> ok = svc.verifyAndConsume(pid, "real@example.com", code);
        assertThat(ok).isPresent();
        assertThat(ok.get().pending().provider()).isEqualTo("discord");
        // 关键：返回的是本次验证通过的邮箱，不是 provider 预填的未验证地址
        assertThat(ok.get().verifiedEmail()).isEqualTo("real@example.com");

        // 一次性：再验第二次失败（已消费）
        assertThat(svc.verifyAndConsume(pid, "real@example.com", code)).isEmpty();
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
        assertThat(svc.verifyAndConsume(pid, "attacker@e.com", code)).isEmpty();
    }

    @Test
    void resendDoesNotResetAttempts_bruteForceCapped() {
        String pid = svc.begin(reg("a@e.com"));
        sendAndCaptureCode(pid, "a@e.com");
        // 打满 5 次错码
        for (int i = 0; i < 5; i++) {
            assertThat(svc.verifyAndConsume(pid, "a@e.com", "111111")).isEmpty();
        }
        // 过冷却后重发（新码），但 attempts 不重置
        ticker.advance(Duration.ofSeconds(61));
        String newCode = sendAndCaptureCode(pid, "a@e.com");
        // 即便拿到正确的新码，也因 attempts 已耗尽而失败——这就是堵住"重发无限爆破"
        assertThat(svc.verifyAndConsume(pid, "a@e.com", newCode)).isEmpty();
    }

    @Test
    void resendCooldownEnforced() {
        String pid = svc.begin(reg("a@e.com"));
        when(email.sendHtml(anyString(), anyString(), anyString())).thenReturn(true);
        assertThat(svc.sendOtp(pid, "a@e.com")).isEqualTo(SendResult.SENT);
        // 60s 内再发 → 限流
        assertThat(svc.sendOtp(pid, "a@e.com")).isEqualTo(SendResult.RATE_LIMITED);
        ticker.advance(Duration.ofSeconds(61));
        assertThat(svc.sendOtp(pid, "a@e.com")).isEqualTo(SendResult.SENT);
    }

    @Test
    void resendCapEnforced() {
        String pid = svc.begin(reg("a@e.com"));
        when(email.sendHtml(anyString(), anyString(), anyString())).thenReturn(true);
        for (int i = 0; i < 5; i++) {
            assertThat(svc.sendOtp(pid, "a@e.com")).isEqualTo(SendResult.SENT);
            ticker.advance(Duration.ofSeconds(61)); // 过冷却
        }
        // 第 6 封超上限
        assertThat(svc.sendOtp(pid, "a@e.com")).isEqualTo(SendResult.RATE_LIMITED);
    }

    @Test
    void otpExpiresAfterTtl() {
        String pid = svc.begin(reg("a@e.com"));
        String code = sendAndCaptureCode(pid, "a@e.com");
        ticker.advance(Duration.ofMinutes(11)); // 超 10min OTP TTL
        assertThat(svc.verifyAndConsume(pid, "a@e.com", code)).isEmpty();
    }

    @Test
    void devConsoleFallbackWhenResendUnconfigured() {
        ResendEmailService devEmail = mock(ResendEmailService.class);
        when(devEmail.isConfigured()).thenReturn(false); // 本地没配 Resend
        RegistrationService dev = new RegistrationService(devEmail, ticker, true);

        String pid = dev.begin(reg("a@e.com"));
        // 不真发信，但流程照走（返回 SENT），验证码只进日志——贡献者本地读控制台即可
        assertThat(dev.sendOtp(pid, "a@e.com")).isEqualTo(SendResult.SENT);
        verify(devEmail, never()).sendHtml(anyString(), anyString(), anyString());

        // 关键：兜底必须发生在会话状态写入**之后**。若今后把 isConfigured() 检查上提到
        // 生成验证码之前（很自然的 fail-fast 重构），otpCode/otpEmail 就不会被写入，
        // 本地拿到码也 verifyAndConsume 不过 —— 本地流程静默坏掉而上面两条断言照过。
        // 用"限流状态已计数"来锁定这个副作用。
        assertThat(dev.getPending(pid)).isPresent();
        assertThat(dev.sendOtp(pid, "a@e.com"))
                .as("已发过一次，冷却期内再发应被限流——证明会话状态确实写进去了")
                .isEqualTo(SendResult.RATE_LIMITED);
    }

    @Test
    void withoutDevSwitchUnconfiguredResendIsARetryableFailure() {
        // 没开 dev 开关时，Resend 未配置必须回到"发信失败"（可重试），而不是假装已发送——
        // 否则生产掉了 key 就会让用户永远卡在输码页。
        ResendEmailService prodEmail = mock(ResendEmailService.class);
        when(prodEmail.isConfigured()).thenReturn(false);
        when(prodEmail.sendHtml(anyString(), anyString(), anyString())).thenReturn(false);
        RegistrationService prod = new RegistrationService(prodEmail, ticker, false);

        String pid = prod.begin(reg("a@e.com"));
        assertThat(prod.sendOtp(pid, "a@e.com")).isEqualTo(SendResult.SEND_FAILED);
    }

    @Test
    void invalidEmailRejected() {
        String pid = svc.begin(reg("a@e.com"));
        assertThat(svc.sendOtp(pid, "not-an-email")).isEqualTo(SendResult.INVALID_EMAIL);
    }

    @Test
    void unknownPendingIsSessionExpired() {
        assertThat(svc.sendOtp("nonexistent", "a@e.com")).isEqualTo(SendResult.SESSION_EXPIRED);
        assertThat(svc.verifyAndConsume("nonexistent", "a@e.com", "123456")).isEmpty();
    }

    @Test
    void verifyFailsWhenNothingSent() {
        String pid = svc.begin(reg("a@e.com"));
        assertThat(svc.verifyAndConsume(pid, "a@e.com", "123456")).isEmpty();
    }

    @Test
    void concurrentConsumeSucceedsExactlyOnce() throws Exception {
        String pid = svc.begin(reg("a@e.com"));
        String code = sendAndCaptureCode(pid, "a@e.com");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                try {
                    start.await();
                    if (svc.verifyAndConsume(pid, "a@e.com", code).isPresent()) {
                        successes.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        // 并发多线程用同一正确码，只能成功一次（原子一次性消费）
        assertThat(successes.get()).isEqualTo(1);
    }
}
