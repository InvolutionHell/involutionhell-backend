package com.involutionhell.backend.usercenter.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.oauth.AuthProvider;
import com.involutionhell.backend.usercenter.oauth.AuthProviderRegistry;
import com.involutionhell.backend.usercenter.service.AuthService;
import com.involutionhell.backend.usercenter.service.RegistrationService;
import com.involutionhell.backend.usercenter.service.UserIdentityService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 第三方 OAuth 的两条流程，共用同一个回调端点：
 *   1. **登录**：/oauth/render/{provider} → 授权 → 回调 → 登入已有账号发 token；
 *      新用户不建号，带 ih_reg_pending cookie 去 /login/verify 验证邮箱（AuthController /auth/register/*）
 *   2. **绑定（M2b）**：POST /oauth/bind/{provider}（需已登录，返回授权地址）→ 授权 → 回调 →
 *      把该身份挂到**当前账号**，不建号、不换会话
 *
 * provider 全部经 {@link AuthProviderRegistry} 查找，本类不含任何 provider 名的 switch；
 * 接入新 provider 见 usercenter/README.md。
 */
@RestController
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    // Discord 登录灰度白名单：逗号分隔的 Discord user id。配了值=只放行名单内 id
    // （以及已有账号的回访登录），其余人在回调处被弹回 /login?error=discord_canary；
    // 完全不配=闸关闭，对所有人开放（GA 就是清空它）。
    @Value("${auth.discord.allowlist:}")
    private String discordAllowlistRaw;

    // 闸是否生效。由"是否配了非空值"决定，与解析出的 id 个数无关——配了值却解析不出
    // 任何 id（只剩逗号/引号）时必须保持关闭状态而不是悄悄放开，见 initDiscordAllowlist。
    private boolean discordGateActive;
    private Set<String> discordAllowlist = Set.of();

    @Value("${AUTH_URL:http://localhost:3000}")
    private String frontEndUrl;

    private final AuthService authService;
    private final UserIdentityService userIdentityService;
    private final AuthProviderRegistry providers;

    /**
     * 绑定意图：state → 发起绑定的 userId。
     *
     * INV-007 的红线是"绑定目标账号绝不能取自 state"——攻击者可以伪造 state 内容。
     * 所以这里只把 state 当**不可猜测的查找键**，真正的 userId 来自发起绑定那一刻
     * 服务端已校验的会话（@SaCheckLogin），存在服务端内存里，攻击者改不了。
     * TTL 与 state cookie 同为 5 分钟，一次性消费。
     */
    private final Cache<String, Long> bindIntents = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(STATE_COOKIE_MAX_AGE_SECONDS))
            .maximumSize(10_000)
            .build();

    public OAuthController(AuthService authService,
                           UserIdentityService userIdentityService,
                           AuthProviderRegistry providers) {
        this.authService = authService;
        this.userIdentityService = userIdentityService;
        this.providers = providers;
    }

    /**
     * 启动时解析白名单并**明确播报当前处于哪种模式**。缺 env 与故意 GA 在行为上
     * 无法区分，只能靠这条日志区分——没有它，一次丢掉 AUTH_DISCORD_ALLOWLIST 的
     * 部署会静默地把 Discord 对全网打开，而唯一信号是"没有拒绝日志"。
     */
    @jakarta.annotation.PostConstruct
    void initDiscordAllowlist() {
        configureDiscordAllowlist(discordAllowlistRaw);
    }

    // 与 @PostConstruct 分开，便于单测直接喂各种畸形取值。
    void configureDiscordAllowlist(String raw) {
        this.discordGateActive = raw != null && !raw.isBlank();
        this.discordAllowlist = parseAllowlist(raw);
        if (!discordGateActive) {
            log.warn("[Discord 灰度] 未配置 auth.discord.allowlist → 闸关闭，Discord 登录对所有人开放");
        } else if (discordAllowlist.isEmpty()) {
            // 配了值却一个 id 都解析不出（典型：清列表时手滑留了个逗号）。保持闸关闭状态
            // 拒绝所有人——错误方向选"没人能登"而不是"所有人能登"，并且必须吼出来。
            log.error("[Discord 灰度] auth.discord.allowlist 配了值但解析不出任何 id（只剩逗号/引号？）"
                    + " → 所有 Discord 登录都会被拒绝，包括本应放行的人");
        } else {
            log.info("[Discord 灰度] 闸已启用，{} 个 id 在白名单内；其余新用户会被拒", discordAllowlist.size());
        }
    }

    /**
     * 逗号分隔 → id 集合。丢掉空项（"a,,b" / 尾逗号）并剥掉引号——docker-compose 的
     * env_file 不剥引号，AUTH_DISCORD_ALLOWLIST="123" 会把引号一起带进来，
     * trim() 处理不了，会导致白名单本人也匹配不上。
     */
    static Set<String> parseAllowlist(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .map(s -> s.replaceAll("^[\"']+|[\"']+$", "").trim())
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * 灰度放行判定。闸未启用=全放行（GA）。启用时放行两类人：白名单内的 id，以及
     * **已经有账号的回访用户**——灰度要挡的是"新 Discord 用户进注册"，不是把已有账号的人
     * 锁在自己账号外面。OTP 注册上线后闸已 GA（不配即全开），保留它作为一键收回的回滚开关。
     *
     * 绑定流程（/oauth/bind）不经过这里：绑定不建号，不产生分叉风险。
     */
    boolean discordAllowed(String discordUserId) {
        if (!discordGateActive) {
            return true;
        }
        if (discordUserId == null || discordUserId.isBlank()) {
            return false;
        }
        if (discordAllowlist.contains(discordUserId)) {
            return true;
        }
        return authService.hasIdentity("discord", discordUserId);
    }

    // OAuth state 双提交 cookie 名。INV-007：callback 校验 URL state 必须等于此 cookie，
    // 二者都由本次 render 生成——防登录 CSRF（攻击者无法向受害者浏览器种此 cookie）。
    static final String STATE_COOKIE = "ih_oauth_state";
    // 绑定流程标记。意图存在内存里，过期/重放/重启都会丢——没有这个标记，回调只能把
    // 丢了意图的绑定当成登录处理，按该第三方身份建号或登进别的账号。
    static final String BIND_COOKIE = "ih_oauth_bind";
    private static final int STATE_COOKIE_MAX_AGE_SECONDS = 300;
    // 标记必须比 state cookie 活得久：两者同时过期的话，在 provider 页停留超过 5 分钟的绑定
    // 回来时什么都不剩，会被当成登录失败送去 /login（登录页再点 Discord 就是分叉出新账号）。
    static final int BIND_COOKIE_MAX_AGE_SECONDS = 1800;
    // 待注册会话（OTP）的 pendingId。AuthController 的 /auth/register/* 读它。
    public static final String REG_COOKIE = "ih_reg_pending";
    // 登录 nonce 回显（INV-007）：前端发起时生成随机 n 存 sessionStorage，render 把它种进
    // httpOnly cookie，登录成功时回显成 #token=..&n=..，前端只认与本 tab 存的 n 相等的 token。
    // 攻击者能把受害者 tab 导航到自己的 #token=，但拿不到受害者的 n。
    static final String LOGIN_NONCE_COOKIE = "ih_login_nonce";
    private static final Pattern LOGIN_NONCE_RE = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");

    /**
     * 发起登录：跳 provider 授权页。n 不合法或缺失就不种 nonce cookie——老前端照常能登，
     * 回调回落成不带 n 的 #token=（新前端会拒收它，见 INV-007）。
     */
    @GetMapping("/oauth/render/{provider}")
    public void renderAuth(@PathVariable String provider,
                           @RequestParam(required = false) String n,
                           HttpServletResponse response) throws IOException {
        Optional<String> url = startOAuth(provider, null, response);
        if (url.isPresent() && n != null && LOGIN_NONCE_RE.matcher(n).matches()) {
            response.addHeader("Set-Cookie", buildCookie(LOGIN_NONCE_COOKIE, n, STATE_COOKIE_MAX_AGE_SECONDS));
        }
        response.sendRedirect(url.orElse(frontEndUrl + "/login?error=oauth_provider"));
    }

    /**
     * 发起绑定（M2b）：把 {provider} 挂到**当前登录账号**，返回授权地址由前端跳转。
     * @SaCheckLogin 保证 userId 来自服务端校验过的会话，而不是任何客户端可控输入。
     * 必须是 POST：GET 能被跨站顶级导航触发，配合浏览器里被种下的别人的 satoken cookie
     * （SameSite=Lax 照样随 GET 导航发送），就能把受害者的第三方身份绑进攻击者账号。
     */
    @SaCheckLogin
    @PostMapping("/oauth/bind/{provider}")
    public ResponseEntity<ApiResponse<?>> startBind(@PathVariable String provider, HttpServletResponse response) {
        return startOAuth(provider, StpUtil.getLoginIdAsLong(), response)
                .<ResponseEntity<ApiResponse<?>>>map(url -> ResponseEntity.ok(ApiResponse.ok(Map.of("url", url))))
                .orElseGet(() -> ResponseEntity.badRequest().body(ApiResponse.fail("不支持的登录方式")));
    }

    /**
     * 登录与绑定共用的发起逻辑；bindUserId 非空即为绑定流程。
     * 返回 provider 授权地址并种好 cookie；provider 未知/未配置时返回 empty，什么都不种。
     */
    private Optional<String> startOAuth(String provider, Long bindUserId, HttpServletResponse response) {
        Optional<AuthProvider> found = providers.find(provider);
        if (found.isEmpty()) {
            log.warn("[OAuth] 未知 provider={}", provider);
            return Optional.empty();
        }
        AuthProvider p = found.get();
        AuthRequest authRequest;
        try {
            authRequest = p.newRequest();
        } catch (IllegalArgumentException e) {
            log.warn("[OAuth] provider={} 未配置: {}", provider, e.getMessage());
            return Optional.empty();
        }
        String state = me.zhyd.oauth.utils.AuthStateUtils.createState();
        if (bindUserId != null) {
            bindIntents.put(state, bindUserId);
        }
        log.info("[OAuth] {} provider={}, redirect_uri={}",
                bindUserId != null ? "bind" : "render", provider, p.redirectUri());
        // state 同时种进 httpOnly cookie。SameSite=Lax 是关键：callback 是 provider 发起的
        // 跨站顶级导航，Strict 会剥掉 cookie；Lax 恰好在顶级 GET 导航时携带。
        response.addHeader("Set-Cookie", buildCookie(STATE_COOKIE, state, STATE_COOKIE_MAX_AGE_SECONDS));
        // 登录发起时清掉可能残留的绑定标记，否则放弃绑定后的登录会被误判成绑定
        response.addHeader("Set-Cookie", bindUserId != null
                ? buildCookie(BIND_COOKIE, "1", BIND_COOKIE_MAX_AGE_SECONDS)
                : buildCookie(BIND_COOKIE, "", 0));
        return Optional.of(authRequest.authorize(state));
    }

    private String buildCookie(String name, String value, int maxAgeSeconds) {
        return org.springframework.http.ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(frontEndUrl.startsWith("https"))   // 本地 http 下不置 Secure，否则浏览器不回传
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build()
                .toString();
    }

    private String readCookie(jakarta.servlet.http.HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    /**
     * OAuth 回调，登录与绑定共用。走哪条路由由服务端的绑定意图决定（见 bindIntents），
     * 不由任何请求参数决定。
     */
    @GetMapping("/api/auth/callback/{provider}")
    public void login(@PathVariable String provider,
                      @RequestParam(required = false) String code,
                      @RequestParam(required = false) String state,
                      jakarta.servlet.http.HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        // 意图在最前面取出并消费（一次性），后续所有错误分支才知道该跳登录页还是设置页。
        Long bindUserId = state == null ? null : bindIntents.asMap().remove(state);
        boolean isBind = bindUserId != null;
        // 浏览器说"我在绑定"但服务端没有意图（过期/重放/重启）：绝不能落进登录分支
        boolean bindMarker = "1".equals(readCookie(request, BIND_COOKIE));
        boolean bindFlow = isBind || bindMarker;
        response.addHeader("Set-Cookie", buildCookie(BIND_COOKIE, "", 0)); // 用完即清
        String loginNonce = readCookie(request, LOGIN_NONCE_COOKIE);
        response.addHeader("Set-Cookie", buildCookie(LOGIN_NONCE_COOKIE, "", 0)); // 同上，任何出口都清

        // 参数缺失（用户直接访问 / provider 异常回调）统一兜底到前端错误页。
        if (code == null || state == null) {
            log.warn("[OAuth] {} callback missing code/state (direct access?), redirecting", provider);
            response.sendRedirect(errorUrl(bindFlow, "oauth_failed"));
            return;
        }

        // INV-007：state 必须等于本次 render 种下的 cookie（双提交校验）。缺失/不匹配
        // 即拒绝，且在换 token 之前——不给伪造 state 触发登录的机会，也不白打 provider。
        String cookieState = readCookie(request, STATE_COOKIE);
        response.addHeader("Set-Cookie", buildCookie(STATE_COOKIE, "", 0)); // 用完即清
        if (cookieState == null || !cookieState.equals(state)) {
            log.warn("[OAuth] {} state 与 cookie 不匹配（CSRF 或 cookie 丢失），拒绝", provider);
            response.sendRedirect(errorUrl(bindFlow, "oauth_state"));
            return;
        }

        // 同样在换 token 之前：丢了意图的绑定不值得再打一次 provider
        if (!isBind && bindMarker) {
            log.warn("[OAuth] {} 绑定标记在但意图缺失（过期/重放/重启），拒绝且不当作登录", provider);
            response.sendRedirect(frontEndUrl + "/settings?bind_error=bind_session");
            return;
        }

        Optional<AuthProvider> found = providers.find(provider);
        if (found.isEmpty()) {
            log.warn("[OAuth] callback 未知 provider={}", provider);
            response.sendRedirect(errorUrl(isBind, "oauth_provider"));
            return;
        }
        AuthProvider p = found.get();
        AuthRequest authRequest;
        try {
            authRequest = p.newRequest();
        } catch (IllegalArgumentException e) {
            log.warn("[OAuth] callback provider={} 未配置: {}", provider, e.getMessage());
            response.sendRedirect(errorUrl(isBind, "oauth_provider"));
            return;
        }

        AuthCallback callback = new AuthCallback();
        callback.setCode(code);
        callback.setState(state);
        AuthResponse<?> authResponse = authRequest.login(callback);

        if (!authResponse.ok()) {
            log.warn("[OAuth] {} 授权失败: {}", provider, authResponse.getMsg());
            response.sendRedirect(errorUrl(isBind, "oauth_failed"));
            return;
        }

        AuthUser authUser = (AuthUser) authResponse.getData();
        if (isBind) {
            handleBind(p, authUser, bindUserId, response);
        } else {
            handleLogin(p, authUser, loginNonce, response);
        }
    }

    /** 登录：灰度闸 → 登入已有账号发 token / 新用户去验证邮箱。 */
    private void handleLogin(AuthProvider p, AuthUser authUser, String loginNonce, HttpServletResponse response)
            throws IOException {
        // Discord 灰度：不放行的 id 在此弹回（换 token 已发生，但不建号/不登入）。
        // 直连 /oauth/render/discord 绕过前端按钮的人也一并挡在这里。
        if ("discord".equals(p.key()) && !discordAllowed(authUser.getUuid())) {
            log.info("[OAuth] discord 灰度：uuid={} 不在放行范围，拒绝登录", authUser.getUuid());
            revokeQuietly(p, authUser);
            response.sendRedirect(frontEndUrl + "/login?error=discord_canary");
            return;
        }
        AuthService.ProviderLogin result;
        try {
            result = authService.loginByProvider(p.key(), authUser);
        } catch (AuthService.AccountDisabledException e) {
            log.info("[OAuth] {} uuid={} 命中的账号已禁用，拒绝登录", p.key(), authUser.getUuid());
            revokeQuietly(p, authUser);
            response.sendRedirect(frontEndUrl + "/login?error=account_disabled");
            return;
        } catch (Exception e) {
            // 不兜住就是一个裸 500 白页（这是顶级导航，不是 fetch）
            log.error("[OAuth] {} 登录处理失败", p.key(), e);
            revokeQuietly(p, authUser);
            response.sendRedirect(frontEndUrl + "/login?error=oauth_failed");
            return;
        }
        switch (result) {
            // token 放 URL fragment（#token=），不进服务器日志/Referer；前端核对 n 后读入 localStorage
            case AuthService.LoggedIn(LoginResponse r) -> response.sendRedirect(frontEndUrl + "/#token=" + enc(r.tokenValue())
                    + (loginNonce == null ? "" : "&n=" + enc(loginNonce)));
            case AuthService.NeedsRegistration(String pendingId) -> {
                // pendingId 只进 httpOnly cookie、绝不进 URL：URL 里的 pendingId 可以被转发给别人，
                // 受害者在攻击者的注册会话里验证自己的邮箱 → 攻击者的第三方身份拿到受害者的已验证邮箱
                // → 受害者日后用 GitHub 登录按邮箱自动挂靠进攻击者账号。cookie 种不进别人的浏览器。
                response.addHeader("Set-Cookie", buildCookie(REG_COOKIE, pendingId,
                        (int) RegistrationService.SESSION_TTL.toSeconds()));
                response.sendRedirect(frontEndUrl + "/login/verify");
            }
        }
    }

    /**
     * 绑定：把该第三方身份挂到发起绑定的账号上。不建号、不换会话、不发新 token。
     *
     * 二次核对当前会话仍是发起人——意图虽然存在服务端，但从发起到回调之间用户可能
     * 已经登出或换号登录，那时把身份绑到旧 userId 上就是错的账号。
     */
    private void handleBind(AuthProvider p, AuthUser authUser, long bindUserId, HttpServletResponse response)
            throws IOException {
        if (!StpUtil.isLogin() || StpUtil.getLoginIdAsLong() != bindUserId) {
            log.warn("[OAuth] bind provider={} 会话与发起人不一致，拒绝绑定", p.key());
            revokeQuietly(p, authUser);
            response.sendRedirect(frontEndUrl + "/settings?bind_error=bind_session");
            return;
        }
        try {
            userIdentityService.bind(bindUserId, p.key(), authUser.getUuid(),
                    authUser.getEmail(), displayNameOf(authUser));
            log.info("[OAuth] bind 成功 provider={} userId={}", p.key(), bindUserId);
            response.sendRedirect(frontEndUrl + "/settings?bind=ok");
        } catch (UserIdentityService.IdentityAlreadyBoundException e) {
            // 该第三方身份已经属于另一个账号（UNIQUE(provider, provider_user_id)）。
            // 这正是"先 GA 再做 M2b"会陷进去的死局：分叉账号占着身份，本尊补绑不进来。
            log.info("[OAuth] bind 冲突 provider={}: {}", p.key(), e.getMessage());
            revokeQuietly(p, authUser);
            response.sendRedirect(frontEndUrl + "/settings?bind_error=" + e.errorCode());
            return;
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String displayNameOf(AuthUser authUser) {
        return authUser.getNickname() != null ? authUser.getNickname() : authUser.getUsername();
    }

    /** 拒绝/失败后撤销刚换到的 token——不让用户白授权还留着我们用不上的凭据。失败不阻断。 */
    private void revokeQuietly(AuthProvider p, AuthUser authUser) {
        if (authUser == null) {
            return;
        }
        try {
            p.revokeToken(authUser.getToken());
        } catch (Exception e) {
            log.warn("[OAuth] 撤销 {} token 失败（不影响主流程）: {}", p.key(), e.getClass().getSimpleName());
        }
    }

    private String errorUrl(boolean isBind, String code) {
        return frontEndUrl + (isBind ? "/settings?bind_error=" : "/login?error=") + code;
    }
}
