package com.involutionhell.backend.usercenter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.involutionhell.backend.common.email.ResendEmailService;
import com.involutionhell.backend.usercenter.model.PendingRegistration;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 注册时的邮箱 OTP 引擎（ADR-001 的"规范邮箱"子系统）。职责聚焦：待注册态 +
 * 验证码生成/发信/校验，全进程内（Caffeine，短 TTL，不落库）。账号的建号/关联
 * 落地由调用方（AuthService 的 wiring）在 OTP 通过后做。
 *
 * 只在"第三方登录首次、且未被已验证邮箱自动关联"时进入，不是每次登录。
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;
    private static final Duration PENDING_TTL = Duration.ofMinutes(15);
    private static final Duration OTP_TTL = Duration.ofMinutes(10);

    private final ResendEmailService emailService;

    private final Cache<String, PendingRegistration> pending = Caffeine.newBuilder()
            .expireAfterWrite(PENDING_TTL).maximumSize(10_000).build();
    private final Cache<String, Otp> otps = Caffeine.newBuilder()
            .expireAfterWrite(OTP_TTL).maximumSize(10_000).build();

    public RegistrationService(ResendEmailService emailService) {
        this.emailService = emailService;
    }

    /** OTP 挑战：目标邮箱 + 6 位码 + 尝试计数（限次防爆破）。 */
    private record Otp(String email, String code, AtomicInteger attempts) {}

    /** 建一个待注册态，返回 pendingId（后续 send/verify 用它引用）。 */
    public String begin(PendingRegistration reg) {
        String pendingId = UUID.randomUUID().toString();
        pending.put(pendingId, reg);
        return pendingId;
    }

    public Optional<PendingRegistration> getPending(String pendingId) {
        return Optional.ofNullable(pending.getIfPresent(pendingId));
    }

    /**
     * 给指定邮箱发 6 位验证码。pending 必须存在（否则视为过期/非法）。
     * 发信失败返回 false（调用方作可重试用户错误处理，不 500）。
     */
    public boolean sendOtp(String pendingId, String email) {
        if (pending.getIfPresent(pendingId) == null) {
            return false; // 待注册态已过期或不存在
        }
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        otps.put(pendingId, new Otp(email, code, new AtomicInteger(0)));
        String html = "<p>你的 InvolutionHell 注册验证码是：</p>"
                + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:4px\">" + code + "</p>"
                + "<p>10 分钟内有效。如果不是你本人操作，忽略即可。</p>";
        boolean sent = emailService.sendHtml(email, "InvolutionHell 注册验证码", html);
        if (!sent) {
            log.warn("OTP 发信失败 pendingId={} email={}", pendingId, email);
        }
        return sent;
    }

    /**
     * 校验验证码。成功则消费掉待注册态与 OTP，返回待注册意图供调用方落地建号；
     * 失败返回空（过期 / 邮箱不符 / 码错 / 超次）。邮箱必须与发码时一致，防止
     * 换邮箱绕过。
     */
    public Optional<PendingRegistration> verifyAndConsume(String pendingId, String email, String code) {
        Otp otp = otps.getIfPresent(pendingId);
        PendingRegistration reg = pending.getIfPresent(pendingId);
        if (otp == null || reg == null) {
            return Optional.empty(); // 过期
        }
        if (otp.attempts().incrementAndGet() > MAX_ATTEMPTS) {
            otps.invalidate(pendingId); // 超次作废，逼重发
            return Optional.empty();
        }
        if (!otp.email().equalsIgnoreCase(email) || !otp.code().equals(code)) {
            return Optional.empty();
        }
        // 成功：一次性消费
        otps.invalidate(pendingId);
        pending.invalidate(pendingId);
        return Optional.of(reg);
    }
}
