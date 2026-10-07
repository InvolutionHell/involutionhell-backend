package com.involutionhell.backend.sso.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.involutionhell.backend.common.error.AccessDeniedBusinessException;
import com.involutionhell.backend.sso.config.SsoProperties;
import com.involutionhell.backend.sso.dto.SsoCodeRequest;
import com.involutionhell.backend.sso.dto.SsoTokenRequest;
import com.involutionhell.backend.sso.dto.SsoUserInfo;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * IH 通行证（INV-010）：授权码 + PKCE(S256)，让 client 表里登记过的站点用 IH 账号登录。
 * 码只存在本进程内存里（Caffeine），60 秒过期、只能换一次；重启即全部作废，用户重新点一次登录即可。
 */
@Service
@EnableConfigurationProperties(SsoProperties.class)
public class SsoService {

    private static final Logger log = LoggerFactory.getLogger(SsoService.class);

    static final Duration CODE_TTL = Duration.ofSeconds(60);
    // 用 matcher().matches() 整串匹配：Java 的 $ 会放过结尾的换行，这里不写 ^$
    private static final Pattern CODE_CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CODE_VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 签出去、还没换掉的码，记下换码时要核对的东西。 */
    private record PendingCode(long userId, String clientId, String redirectUri, String codeChallenge) {
    }

    /** 只放已启用的 client。HashMap：clientId 为 null 时 get 返回 null 而不是抛 NPE。 */
    private final Map<String, SsoProperties.Client> clients = new HashMap<>();
    private final UserAccountRepository users;
    private final Cache<String, PendingCode> codes;

    @Autowired
    public SsoService(SsoProperties properties, UserAccountRepository users) {
        this(properties, users, Ticker.systemTicker());
    }

    /** 供测试注入假时钟，验证码到期作废。 */
    SsoService(SsoProperties properties, UserAccountRepository users, Ticker ticker) {
        this.users = users;
        this.codes = Caffeine.newBuilder()
                .ticker(ticker)
                .expireAfterWrite(CODE_TTL)
                .maximumSize(10_000)
                .build();
        // 启动时播报哪些 client 生效（参照 INV-008「不得静默失效」）。不打 secret。
        properties.clients().forEach((id, client) -> {
            if (client.enabled()) {
                clients.put(id, client);
                log.info("[SSO] client {} 已启用，回跳地址 {}", id, client.redirectUri());
            } else {
                log.warn("[SSO] client {} 缺 secret 或 redirect-uri → 未启用，签码/换码都会拒绝", id);
            }
        });
        if (clients.isEmpty()) {
            log.warn("[SSO] 没有启用任何 client，IH 通行证不可用");
        }
    }

    /**
     * 给当前登录用户签授权码（POST /oauth/sso/code），返回带 code 和 state 的回跳地址。
     * 参数不合法抛 IllegalArgumentException（→ 400），账号不可用抛 AccessDeniedBusinessException（→ 403）。
     */
    public String issueCode(long userId, SsoCodeRequest request) {
        SsoProperties.Client client = clients.get(request.clientId());
        if (client == null) {
            throw new IllegalArgumentException("未知或未启用的 client_id");
        }
        // 逐字相等：不做前缀、通配，也不归一化大小写、尾斜杠、query
        if (!client.redirectUri().equals(request.redirectUri())) {
            throw new IllegalArgumentException("redirect_uri 与登记的地址不一致");
        }
        String state = request.state();
        if (state == null || state.isEmpty() || state.length() > 512) {
            throw new IllegalArgumentException("state 长度必须在 1 到 512 之间");
        }
        if (request.codeChallenge() == null || !CODE_CHALLENGE.matcher(request.codeChallenge()).matches()) {
            throw new IllegalArgumentException("code_challenge 必须是 43 位 base64url");
        }
        if (!"S256".equals(request.codeChallengeMethod())) {
            throw new IllegalArgumentException("code_challenge_method 必须是 S256");
        }
        if (users.findById(userId).filter(UserAccount::enabled).isEmpty()) {
            throw new AccessDeniedBusinessException("账号已被禁用");
        }

        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String code = BASE64URL.encodeToString(random);
        codes.put(code, new PendingCode(userId, request.clientId(), client.redirectUri(), request.codeChallenge()));
        return client.redirectUri() + "?code=" + code + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    /**
     * 换码接口的 client 鉴权：client 已启用，且 secret 常量时间比较相等。
     * 必须先于 {@link #redeem} 调用——没过鉴权的请求不能碰码（否则谁都能把别人的码烧掉）。
     */
    public boolean authenticateClient(String clientId, String clientSecret) {
        SsoProperties.Client client = clients.get(clientId);
        if (client == null || clientSecret == null) {
            return false;
        }
        boolean ok = MessageDigest.isEqual(
                client.secret().getBytes(StandardCharsets.UTF_8),
                clientSecret.getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            // clientId 此时是登记过的值，可以放心进日志
            log.warn("[SSO] client {} 换码时带的 secret 不对", clientId);
        }
        return ok;
    }

    /**
     * 用授权码换用户资料（POST /internal/sso/token），调用方须已通过 {@link #authenticateClient}。
     * 取码用 asMap().remove：取出和作废是同一个原子操作，码只能用一次；
     * 之后任何一项核对不上，码也已经没了，不能重试。返回 empty 即 invalid_grant。
     */
    public Optional<SsoUserInfo> redeem(SsoTokenRequest request) {
        PendingCode pending = request.code() == null ? null : codes.asMap().remove(request.code());
        if (pending == null) {
            return invalidGrant(request, "码不存在、已用过或已过期");
        }
        if (!pending.clientId().equals(request.clientId()) || !pending.redirectUri().equals(request.redirectUri())) {
            return invalidGrant(request, "clientId 或 redirectUri 与签码时不一致");
        }
        String verifier = request.codeVerifier();
        if (verifier == null || !CODE_VERIFIER.matcher(verifier).matches()
                || !MessageDigest.isEqual(s256(verifier), pending.codeChallenge().getBytes(StandardCharsets.US_ASCII))) {
            return invalidGrant(request, "code_verifier 不对");
        }
        // 签码到换码之间账号可能被禁用，再查一次
        Optional<UserAccount> account = users.findById(pending.userId()).filter(UserAccount::enabled);
        if (account.isEmpty()) {
            return invalidGrant(request, "账号不存在或已被禁用");
        }
        UserAccount user = account.get();
        log.info("[SSO] client {} 换码成功 userId={}", request.clientId(), user.id());
        return Optional.of(new SsoUserInfo(
                String.valueOf(user.id()), user.username(), user.displayName(), user.avatarUrl()));
    }

    private static Optional<SsoUserInfo> invalidGrant(SsoTokenRequest request, String reason) {
        log.warn("[SSO] client {} 换码失败：{}", request.clientId(), reason);
        return Optional.empty();
    }

    /** base64url(sha256(verifier))，无填充，返回 ASCII 字节。 */
    private static byte[] s256(String verifier) {
        try {
            return BASE64URL.encode(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256", e);
        }
    }
}
