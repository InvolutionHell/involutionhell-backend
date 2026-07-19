package com.involutionhell.backend.usercenter.controller;

import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.oauth.AuthDiscordRequest;
import com.involutionhell.backend.usercenter.service.AuthService;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthGithubRequest;
import me.zhyd.oauth.request.AuthRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 第三方 OAuth 登录入口。provider 无关（github 内置 / discord 自定义 source）；
 * github 的特殊性下沉到 AuthService 的业务层。端点路径带 {provider}，github 的
 * /api/auth/callback/github 与 OAuth App 注册的回调 URL 保持一致。
 */
@RestController
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    @Value("${justauth.type.github.client-id:}")
    private String githubClientId;
    @Value("${justauth.type.github.client-secret:}")
    private String githubClientSecret;
    @Value("${justauth.type.github.redirect-uri:}")
    private String githubRedirectUri;

    @Value("${justauth.type.discord.client-id:}")
    private String discordClientId;
    @Value("${justauth.type.discord.client-secret:}")
    private String discordClientSecret;
    @Value("${justauth.type.discord.redirect-uri:}")
    private String discordRedirectUri;

    @Value("${AUTH_URL:http://localhost:3000}")
    private String frontEndUrl;

    private final AuthService authService;

    public OAuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 按 provider 造 AuthRequest。未知或未配置 → IllegalArgumentException，
     * 由调用方兜底重定向到错误页（不 500）。
     */
    private AuthRequest authRequestFor(String provider) {
        return switch (provider) {
            case "github" -> {
                requireConfigured("github", githubClientId, githubClientSecret);
                yield new AuthGithubRequest(AuthConfig.builder()
                        .clientId(githubClientId).clientSecret(githubClientSecret)
                        .redirectUri(githubRedirectUri).build());
            }
            case "discord" -> {
                requireConfigured("discord", discordClientId, discordClientSecret);
                yield new AuthDiscordRequest(AuthConfig.builder()
                        .clientId(discordClientId).clientSecret(discordClientSecret)
                        .redirectUri(discordRedirectUri).build());
            }
            default -> throw new IllegalArgumentException("不支持的 OAuth provider: " + provider);
        };
    }

    // client-id 与 secret 都要有：只配一半时提前挡在 oauth_provider（配置问题），
    // 而不是让 token 交换阶段以 oauth_failed 失败——后者会误导成"provider 侧拒绝"。
    private void requireConfigured(String provider, String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException(provider + " OAuth 未配置（缺 client-id 或 secret）");
        }
    }

    // 仅用于排查日志：redirect_uri 是公开信息，不含密钥。
    private String redirectUriOf(String provider) {
        return switch (provider) {
            case "github" -> githubRedirectUri;
            case "discord" -> discordRedirectUri;
            default -> "(n/a)";
        };
    }

    // OAuth state 双提交 cookie 名。INV-007：callback 校验 URL state 必须等于此 cookie，
    // 二者都由本次 render 生成——防登录 CSRF（攻击者无法向受害者浏览器种此 cookie）。
    static final String STATE_COOKIE = "ih_oauth_state";
    private static final int STATE_COOKIE_MAX_AGE_SECONDS = 300;

    /**
     * 构建授权链接并重定向到 provider。前端跳到 /oauth/render/{provider} 发起登录。
     */
    @GetMapping("/oauth/render/{provider}")
    public void renderAuth(@PathVariable String provider, HttpServletResponse response) throws IOException {
        AuthRequest authRequest;
        try {
            authRequest = authRequestFor(provider);
        } catch (IllegalArgumentException e) {
            log.warn("[OAuth] render 未知/未配置 provider={}: {}", provider, e.getMessage());
            response.sendRedirect(frontEndUrl + "/login?error=oauth_provider");
            return;
        }
        log.info("[OAuth] render provider={}, redirect_uri={}", provider, redirectUriOf(provider));
        String state = me.zhyd.oauth.utils.AuthStateUtils.createState();
        // state 同时种进 httpOnly cookie。SameSite=Lax 是关键：callback 是 provider 发起的
        // 跨站顶级导航，Strict 会剥掉 cookie；Lax 恰好在顶级 GET 导航时携带。
        response.addHeader("Set-Cookie", buildStateCookie(state, STATE_COOKIE_MAX_AGE_SECONDS));
        response.sendRedirect(authRequest.authorize(state));
    }

    private String buildStateCookie(String value, int maxAgeSeconds) {
        return org.springframework.http.ResponseCookie.from(STATE_COOKIE, value)
                .httpOnly(true)
                .secure(frontEndUrl.startsWith("https"))   // 本地 http 下不置 Secure，否则浏览器不回传
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds)
                .build()
                .toString();
    }

    private String readStateCookie(jakarta.servlet.http.HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (jakarta.servlet.http.Cookie c : request.getCookies()) {
            if (STATE_COOKIE.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    /**
     * OAuth 回调。github 的路径 /api/auth/callback/github 与 OAuth App 注册一致；
     * discord 走 /api/auth/callback/discord。
     */
    @GetMapping("/api/auth/callback/{provider}")
    public void login(@PathVariable String provider,
                      @RequestParam(required = false) String code,
                      @RequestParam(required = false) String state,
                      jakarta.servlet.http.HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        // 参数缺失（用户直接访问 / provider 异常回调）统一兜底到前端错误页。
        if (code == null || state == null) {
            log.warn("[OAuth] {} callback missing code/state (direct access?), redirecting", provider);
            response.sendRedirect(frontEndUrl + "/login?error=oauth_failed");
            return;
        }

        // INV-007：state 必须等于本次 render 种下的 cookie（双提交校验）。缺失/不匹配
        // 即拒绝，且在换 token 之前——不给伪造 state 触发登录的机会，也不白打 provider。
        String cookieState = readStateCookie(request);
        response.addHeader("Set-Cookie", buildStateCookie("", 0)); // 用完即清
        if (cookieState == null || !cookieState.equals(state)) {
            log.warn("[OAuth] {} state 与 cookie 不匹配（CSRF 或 cookie 丢失），拒绝", provider);
            response.sendRedirect(frontEndUrl + "/login?error=oauth_state");
            return;
        }

        AuthRequest authRequest;
        try {
            authRequest = authRequestFor(provider);
        } catch (IllegalArgumentException e) {
            log.warn("[OAuth] callback 未知/未配置 provider={}: {}", provider, e.getMessage());
            response.sendRedirect(frontEndUrl + "/login?error=oauth_provider");
            return;
        }

        AuthCallback callback = new AuthCallback();
        callback.setCode(code);
        callback.setState(state);
        AuthResponse<?> authResponse = authRequest.login(callback);

        if (authResponse.ok()) {
            AuthUser authUser = (AuthUser) authResponse.getData();
            LoginResponse loginResponse = authService.loginByProvider(provider, authUser);
            // token 放 URL fragment（#token=），不进服务器日志/Referer；前端读入 localStorage
            response.sendRedirect(frontEndUrl + "/#token=" + loginResponse.tokenValue());
        } else {
            log.warn("[OAuth] {} 登录失败: {}", provider, authResponse.getMsg());
            response.sendRedirect(frontEndUrl + "/login?error=oauth_failed");
        }
    }
}
