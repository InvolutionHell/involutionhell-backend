package com.involutionhell.backend.usercenter.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.involutionhell.backend.common.email.ResendEmailService;
import com.involutionhell.backend.usercenter.model.PendingRegistration;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 注册时的邮箱 OTP 引擎（ADR-001 的"规范邮箱"子系统）。职责聚焦：待注册态 +
 * 验证码生成/发信/校验，全进程内不落库。建号由 AuthService#completeRegistration 在
 * OTP 通过后做。只在"第三方登录首次、且未被已验证邮箱自动关联"时进入，不是每次登录。
 *
 * 安全模型（review 后重做）：
 *   - 一个 pendingId 对应一个可变会话 RegSession，从 begin 起固定 15min 生命周期
 *     （不被重发刷新），pending 与 OTP 同生共死，避免两缓存 TTL 错配。
 *   - verifyAttempts 累积计在会话上、**不被重发重置**：整个会话至多 5 次校验尝试，
 *     6 位码的爆破面恒为 5/1e6，堵住"重发归零 attempts 无限爆破"。
 *   - 发信限流：会话内至多 5 封、两封间隔 ≥60s；跨会话每个（收件邮箱, 第三方身份）每天 5 封、
 *     每个收件邮箱每天 20 封、每个第三方身份每天 10 封，防邮件轰炸。先查完所有限额再一起占位，
 *     发信失败退还（没送达的不算数，也不留可猜的码）。第 5 次错码即作废会话。
 *   - 校验+消费在会话锁内原子完成，双击/并发只成功一次，杜绝重复建号。
 *   - 校验通过返回**本次实际验证通过的 email**（不是 provider 预填的未验证地址）。
 *
 * ponytail: 进程内 Caffeine，靠单实例 / sticky session 成立；上多实例或 GraalVM
 * native 时会话态要迁 Redis（同 Sa-Token session、JustAuth state，见 ADR-001）。
 * 针对特定 pendingId 的 attempts 耗尽型 DoS 无从下手：pendingId 是随机 UUID，只在
 * 发起人浏览器的 httpOnly cookie 里，15min 后过期。
 */
@Service
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_VERIFY_ATTEMPTS = 5;
    private static final int MAX_SENDS = 5;
    private static final long RESEND_COOLDOWN_NANOS = Duration.ofSeconds(60).toNanos();
    private static final long OTP_TTL_NANOS = Duration.ofMinutes(10).toNanos();
    /** 会话寿命；OAuthController 的 ih_reg_pending cookie Max-Age 与之同源。 */
    public static final Duration SESSION_TTL = Duration.ofMinutes(15);
    // 跨会话日限额：每个 pendingId 都要一次真实 OAuth 才能拿到，但同一个第三方号可以反复拿。
    // 按（收件邮箱, 第三方身份）限 = 一个号轰炸不了某个邮箱，也锁不死邮箱主人自己的注册——
    //   只按邮箱限 5 的话，攻击者拿自己的号对受害者邮箱发满 5 封，受害者 24h 内就注册不了；
    // 按收件邮箱总量限 = 多个号合伙轰炸同一收件箱的天花板，也封顶针对它的猜码总量（20 个会话 × 5 次）；
    // 按第三方身份限 = 防一个号把我们的发信域名当垃圾邮件中继。
    // ponytail: 不按 IP —— 后端看不到可信客户端 IP（Vercel 出口 IP 被所有人共享，
    // X-Real-IP 由 Vercel 填、Caddy 原样透传，直连 api 子域即可伪造）。
    private static final int MAX_SENDS_PER_EMAIL_IDENTITY_PER_DAY = 5;
    private static final int MAX_SENDS_PER_EMAIL_PER_DAY = 20;
    private static final int MAX_SENDS_PER_IDENTITY_PER_DAY = 10;
    // 粗校验邮箱格式，挡住明显非法/注入型输入；真实有效性由"能收到码"证明。
    private static final Pattern EMAIL_RE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** sendOtp 结果。区分"会话过期(不可恢复,需重走注册)"与"发信失败(可重试)"等语义。 */
    public enum SendResult { SENT, SESSION_EXPIRED, RATE_LIMITED, INVALID_EMAIL, SEND_FAILED }

    /** 校验通过的注册意图 + 本次已验证的规范邮箱。 */
    public record VerifiedRegistration(PendingRegistration pending, String verifiedEmail) {}

    private final ResendEmailService emailService;
    private final Ticker ticker;
    private final boolean devConsoleOtp;
    private final Cache<String, RegSession> sessions;
    private final Cache<String, AtomicInteger> dailySends;

    @org.springframework.beans.factory.annotation.Autowired
    public RegistrationService(
            ResendEmailService emailService,
            @org.springframework.beans.factory.annotation.Value("${registration.otp.dev-console:false}")
            boolean devConsoleOtp) {
        this(emailService, Ticker.systemTicker(), devConsoleOtp);
    }

    /** 供测试注入 FakeTicker，控制 OTP 过期与会话 TTL。 */
    RegistrationService(ResendEmailService emailService, Ticker ticker, boolean devConsoleOtp) {
        this.emailService = emailService;
        this.ticker = ticker;
        this.devConsoleOtp = devConsoleOtp;
        this.sessions = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(SESSION_TTL)
                .maximumSize(10_000)
                .build();
        this.dailySends = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(Duration.ofDays(1))
                .maximumSize(100_000)
                .build();
    }

    private record DailyCap(String key, int cap) {}

    /**
     * 固定 24h 窗口计数（同 OpenAiStreamRateLimiter 的写法）。所有限额都没满才一起占一格，
     * 满一个就一格都不占——不能让被拒的请求白白吃掉别的限额。检查与占位在同一把锁里，
     * 并发会话冲不过上限；发信失败由 {@link #refundDaily} 退还。
     * ponytail: 全局锁，临界区只是几次内存读写；发信量远到不了要分段锁的程度。
     */
    private boolean reserveDaily(List<DailyCap> caps) {
        synchronized (dailySends) {
            for (DailyCap c : caps) {
                AtomicInteger n = dailySends.getIfPresent(c.key());
                if (n != null && n.get() >= c.cap()) {
                    return false;
                }
            }
            caps.forEach(c -> dailySends.get(c.key(), k -> new AtomicInteger()).incrementAndGet());
            return true;
        }
    }

    private void refundDaily(List<DailyCap> caps) {
        synchronized (dailySends) {
            caps.forEach(c -> {
                AtomicInteger n = dailySends.getIfPresent(c.key());
                if (n != null) {
                    n.decrementAndGet();
                }
            });
        }
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
     * 给指定邮箱发 6 位验证码。限流：会话至多 {@value #MAX_SENDS} 封、间隔 ≥60s，加上跨会话日限额。
     * 发信失败不计入任何限额（冷却照算，挡住对 Resend 的连发），且作废本次的码。
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
        String emailKey = "email:" + normalized.toLowerCase(Locale.ROOT);
        String identityKey = "id:" + s.reg.provider() + ":" + s.reg.providerUserId();
        List<DailyCap> caps = List.of(
                new DailyCap(emailKey + "|" + identityKey, MAX_SENDS_PER_EMAIL_IDENTITY_PER_DAY),
                new DailyCap(emailKey, MAX_SENDS_PER_EMAIL_PER_DAY),
                new DailyCap(identityKey, MAX_SENDS_PER_IDENTITY_PER_DAY));
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
            if (!reserveDaily(caps)) {
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
        // 开发兜底：验证码直接打到控制台，让贡献者不配 Resend 也能跑通完整注册流
        // （Django/Rails 的 console email backend 同款）。
        // 必须由**显式开关**驱动，不能只看"Resend 没配"——后者同时也是生产掉 key 的
        // 样子，那样 prod 一旦丢 key 就会假装发送成功，用户永远卡在输码页。
        // 日志只落 pendingId + code，不落收件邮箱（PII，与本文件下方及
        // ResendEmailService 的规则一致）；本地开发者自己知道填的哪个邮箱。
        if (devConsoleOtp && !emailService.isConfigured()) {
            log.warn("[DEV-OTP] 控制台兜底（registration.otp.dev-console=true），未真实发信: pendingId={} code={}",
                    pendingId, code);
            return SendResult.SENT;
        }
        String html = "<p>你的 InvolutionHell 注册验证码是：</p>"
                + "<p style=\"font-size:24px;font-weight:bold;letter-spacing:4px\">" + code + "</p>"
                + "<p>10 分钟内有效。如果不是你本人操作，忽略即可。</p>";
        boolean sent = emailService.sendHtml(to, "InvolutionHell 注册验证码", html);
        if (!sent) {
            synchronized (s) {
                s.otpCode = null; // 没送达的码不能留在会话里被猜
                s.sends--;
            }
            refundDaily(caps);
            // 不落收件邮箱（PII），也不落 pendingId（它是注册会话的唯一凭据）
            log.warn("OTP 发信失败 provider={}", s.reg.provider());
            return SendResult.SEND_FAILED;
        }
        return SendResult.SENT;
    }

    /**
     * 校验验证码。成功则**原子**消费整个会话，返回注册意图 + 本次已验证的规范邮箱。
     * 失败返回空（会话过期 / 未发码 / 码过期 / 超次 / 邮箱或码不符）。邮箱必须与发码
     * 对象一致，防换邮箱绕过。attempts 累积计数，第 {@value #MAX_VERIFY_ATTEMPTS} 次错码即作废会话。
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
                if (++s.verifyAttempts >= MAX_VERIFY_ATTEMPTS) {
                    sessions.invalidate(pendingId); // 锁死即作废：调用方据此提示重新登录，也不再能发信
                }
                return Optional.empty();
            }
            sessions.invalidate(pendingId); // 一次性消费，锁内完成
            return Optional.of(new VerifiedRegistration(s.reg, s.otpEmail));
        }
    }
}
