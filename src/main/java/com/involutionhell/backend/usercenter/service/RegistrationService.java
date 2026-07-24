package com.involutionhell.backend.usercenter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.involutionhell.backend.common.email.ResendEmailService;
import com.involutionhell.backend.usercenter.model.PendingRegistration;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 注册时的邮箱 OTP 引擎（ADR-001 的"规范邮箱"子系统）。职责聚焦：待注册态 +
 * 验证码生成/发信/校验，全进程内不落库。账号建号/关联落地由调用方（wiring）在
 * OTP 通过后做。只在"第三方登录首次、且未被已验证邮箱自动关联"时进入，不是每次登录。
 *
 * 安全模型（review 后重做）：
 *   - 一个 pendingId 对应一个可变会话 RegSession，从 begin 起固定 15min 生命周期
 *     （不被重发刷新），pending 与 OTP 同生共死，避免两缓存 TTL 错配。
 *   - verifyAttempts 累积计在会话上、**不被重发重置**：整个会话至多 5 次校验尝试，
 *     6 位码的爆破面恒为 5/1e6，堵住"重发归零 attempts 无限爆破"。
 *   - 发信限流：会话内至多 5 封、两封间隔 ≥60s，防邮件轰炸。
 *   - 校验+消费在会话锁内原子完成，双击/并发只成功一次，杜绝重复建号。
 *   - 校验通过返回**本次实际验证通过的 email**（不是 provider 预填的未验证地址）。
 *
 * ponytail: 进程内 Caffeine，靠单实例 / sticky session 成立；上多实例或 GraalVM
 * native 时会话态要迁 Redis（同 Sa-Token session、JustAuth state，见 ADR-001）。
 * 针对特定 pendingId 的 attempts 耗尽型 DoS 由"pendingId 是随机 UUID + 15min 短命 +
 * 控制层按 IP 限流"共同兜底，引擎层不额外处理。
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final int MAX_SENDS = 5;
    private static final long RESEND_COOLDOWN_NANOS = Duration.ofSeconds(60).toNanos();
    private static final long OTP_TTL_NANOS = Duration.ofMinutes(10).toNanos();
    private static final Duration SESSION_TTL = Duration.ofMinutes(15);
    // 粗校验邮箱格式，挡住明显非法/注入型输入；真实有效性由"能收到码"证明。
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** sendOtp 结果。区分"会话过期(不可恢复,需重走注册)"与"发信失败(可重试)"等语义。 */
    public enum SendResult { SENT, SESSION_EXPIRED, RATE_LIMITED, INVALID_EMAIL, SEND_FAILED }

    /** 校验通过的注册意图 + 本次已验证的规范邮箱。 */
    public record VerifiedRegistration(PendingRegistration pending, String verifiedEmail) {}

    private final ResendEmailService emailService;
    private final Ticker ticker;
    private final Cache<String, RegSession> sessions;

    @org.springframework.beans.factory.annotation.Autowired
    public RegistrationService(ResendEmailService emailService) {
        this(emailService, Ticker.systemTicker());
    }

    /** 供测试注入 FakeTicker，控制 OTP 过期与会话 TTL。 */
    RegistrationService(ResendEmailService emailService, Ticker ticker) {
        this.emailService = emailService;
        this.ticker = ticker;
        this.sessions = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(SESSION_TTL)
                .maximumSize(10_000)
                .build();
    }

    /** 一个注册会话的可变状态。所有读改在会话锁内完成。 */
    private static final class RegSession {
        final PendingRegistration reg;
        String otpEmail;
        String otpCode;
        long otpExpiresAtNanos;
        int verifyAttempts;               // 累积，不被重发重置
        int sends;
        long lastSendAtNanos = Long.MIN_VALUE;

        RegSession(PendingRegistration reg) {
            this.reg = reg;
        }
    }

    /** 建一个待注册会话，返回 pendingId。会话 15min 后自然过期（不被重发刷新）。 */
    public String begin(PendingRegistration reg) {
        String pendingId = UUID.randomUUID().toString();
        sessions.put(pendingId, new RegSession(reg));
        return pendingId;
    }

    /** 供 wiring 预填验证页（返回 provider 预填邮箱，非已验证）。 */
    public Optional<PendingRegistration> getPending(String pendingId) {
        RegSession s = sessions.getIfPresent(pendingId);
        return s == null ? Optional.empty() : Optional.of(s.reg);
    }

    /**
     * 给指定邮箱发 6 位验证码。限流：会话至多 {@value #MAX_SENDS} 封、间隔 ≥60s。
     * verifyAttempts 不在此重置。
     */
    public SendResult sendOtp(String pendingId, String email) {
        RegSession s = sessions.getIfPresent(pendingId);
        if (s == null) {
            return SendResult.SESSION_EXPIRED;
        }
        String normalized = email == null ? "" : email.trim();
        if (!EMAIL_RE.matcher(normalized).matches()) {
            return SendResult.INVALID_EMAIL;
        }
        String code;
        String to;
        synchronized (s) {
            long now = ticker.read();
            if (s.sends >= MAX_SENDS) {
                return SendResult.RATE_LIMITED;
            }
            if (s.lastSendAtNanos != Long.MIN_VALUE && now - s.lastSendAtNanos < RESEND_COOLDOWN_NANOS) {
                return SendResult.RATE_LIMITED;
            }
            code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
            s.otpEmail = normalized;
            s.otpCode = code;
            s.otpExpiresAtNanos = now + OTP_TTL_NANOS;
            s.sends++;
            s.lastSendAtNanos = now;
            to = normalized;
        }
        String html = "<p>你的 InvolutionHell 注册验证码是：</p>"
                + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:4px\">" + code + "</p>"
                + "<p>10 分钟内有效。如果不是你本人操作，忽略即可。</p>";
        boolean sent = emailService.sendHtml(to, "InvolutionHell 注册验证码", html);
        if (!sent) {
            log.warn("OTP 发信失败 pendingId={}", pendingId); // 不落收件邮箱（PII）
            return SendResult.SEND_FAILED;
        }
        return SendResult.SENT;
    }

    /**
     * 校验验证码。成功则**原子**消费整个会话，返回注册意图 + 本次已验证的规范邮箱。
     * 失败返回空（会话过期 / 未发码 / 码过期 / 超次 / 邮箱或码不符）。邮箱必须与发码
     * 对象一致，防换邮箱绕过。attempts 累积计数，超 {@value #MAX_VERIFY_ATTEMPTS} 即锁死会话。
     */
    public Optional<VerifiedRegistration> verifyAndConsume(String pendingId, String email, String code) {
        RegSession s = sessions.getIfPresent(pendingId);
        if (s == null) {
            return Optional.empty();
        }
        synchronized (s) {
            // 会话锁内再确认未被并发消费（原子一次性）
            if (sessions.getIfPresent(pendingId) == null) {
                return Optional.empty();
            }
            if (s.otpCode == null) {
                return Optional.empty(); // 还没 sendOtp
            }
            if (ticker.read() > s.otpExpiresAtNanos) {
                return Optional.empty(); // OTP 过期
            }
            if (s.verifyAttempts >= MAX_VERIFY_ATTEMPTS) {
                return Optional.empty(); // 已锁死
            }
            String normalized = email == null ? "" : email.trim();
            boolean ok = s.otpEmail.equalsIgnoreCase(normalized) && s.otpCode.equals(code);
            if (!ok) {
                s.verifyAttempts++;
                return Optional.empty();
            }
            sessions.invalidate(pendingId); // 一次性消费，锁内完成
            return Optional.of(new VerifiedRegistration(s.reg, s.otpEmail));
        }
    }
}
