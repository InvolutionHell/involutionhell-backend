package com.involutionhell.backend.usercenter.controller;

import com.involutionhell.backend.usercenter.dto.LoginResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@RestController
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    @Value("${justauth.type.github.client-id}")
    private String githubClientId;

    @Value("${justauth.type.github.client-secret}")
    private String githubClientSecret;

    @Value("${justauth.type.github.redirect-uri}")
    private String githubRedirectUri;

    @Value("${AUTH_URL:http://localhost:3000}")
    private String frontEndUrl;

    // 注入认证服务，用于查询/注册用户并执行 Sa-Token 登录
    private final AuthService authService;

    public OAuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 获取 GitHub 授权请求对象
     */
    private AuthRequest getAuthRequest() {
        return new AuthGithubRequest(AuthConfig.builder()
                .clientId(githubClientId)
                .clientSecret(githubClientSecret)
                .redirectUri(githubRedirectUri)
                .build());
    }

    // OAuth state 双提交 cookie 名。INV-007：callback 校验 URL state 必须等于此 cookie，
    // 二者都由本次 render 生成——防登录 CSRF（攻击者无法向受害者浏览器种此 cookie）。
    static final String STATE_COOKIE = "ih_oauth_state";
    private static final int STATE_COOKIE_MAX_AGE_SECONDS = 300;

    /**
     * 构建授权链接并重定向到 GitHub
     * 前端直接跳转到后端此地址（NEXT_PUBLIC_BACKEND_URL + /oauth/render/github）发起登录
     */
    @GetMapping("/oauth/render/github")
    public void renderAuth(HttpServletResponse response) throws IOException {
        // 打印当前使用的 GitHub Client ID 和 redirect_uri，便于排查 token 配置问题
        log.info("[OAuth] GitHub Client ID = {}, redirect_uri = {}", githubClientId, githubRedirectUri);
        String state = me.zhyd.oauth.utils.AuthStateUtils.createState();
        // 把 state 同时种进 httpOnly cookie。SameSite=Lax 是关键：callback 是 github.com
        // 发起的跨站顶级导航，Strict 会剥掉 cookie；Lax 恰好在顶级 GET 导航时携带。
        response.addHeader("Set-Cookie", buildStateCookie(state, STATE_COOKIE_MAX_AGE_SECONDS));
        AuthRequest authRequest = getAuthRequest();
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
     * GitHub OAuth 回调地址，路径与 GitHub OAuth App 注册保持一致（/api/auth/callback/github）
     * GitHub → localhost:3000/api/auth/callback/github → Next.js rewrite → localhost:8080/api/auth/callback/github
     */
    @GetMapping("/api/auth/callback/github")
    public void login(@RequestParam(required = false) String code,
                      @RequestParam(required = false) String state,
                      jakarta.servlet.http.HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        // 参数缺失时直接走失败分支：若 @RequestParam 保持 required=true，Spring 在进入方法前
        // 就抛 MissingServletRequestParameterException → 默认 500 白屏；
        // 手动 null check 能把 "用户直接访问 / GitHub 异常回调" 统一兜底到前端错误页。
        if (code == null || state == null) {
            log.warn("[OAuth] GitHub callback missing code/state (direct access?), redirecting to error page");
            response.sendRedirect(frontEndUrl + "/login?error=oauth_failed");
            return;
        }

        // INV-007：state 必须等于本次 render 种下的 cookie（双提交校验）。缺失/不匹配
        // 即拒绝，且在换 token 之前——不给伪造 state 触发登录的机会，也不白打 GitHub。
        String cookieState = readStateCookie(request);
        // 用完即清（无论后续成败），避免 cookie 泄漏 / 复用。
        response.addHeader("Set-Cookie", buildStateCookie("", 0));
        if (cookieState == null || !cookieState.equals(state)) {
            log.warn("[OAuth] state 与 cookie 不匹配（可能的 CSRF 或 cookie 丢失），拒绝登录");
            response.sendRedirect(frontEndUrl + "/login?error=oauth_state");
            return;
        }

        AuthCallback callback = new AuthCallback();
        callback.setCode(code);
        callback.setState(state);
        AuthRequest authRequest = getAuthRequest();
        AuthResponse<?> authResponse = authRequest.login(callback);
        
        if (authResponse.ok()) {
            AuthUser authUser = (AuthUser) authResponse.getData();

            // 调用 AuthService.loginByGithub()：查询或自动注册用户，然后执行 Sa-Token 登录
            // 返回的 LoginResponse 包含 tokenName、tokenValue 和用户视图
            LoginResponse loginResponse = authService.loginByGithub(authUser);

            // 登录成功后重定向到前端，将 token 放在 URL fragment（#token=）中传给前端
            // fragment 不会出现在服务器日志和 Referer 头中，避免 token 泄露
            // 前端读取 #token= 参数后存入 localStorage，并清除 URL 中的 fragment
            String redirectUrl = frontEndUrl + "/#token=" + loginResponse.tokenValue();
            response.sendRedirect(redirectUrl);
        } else {
            // 登录失败，重定向回前端并带上错误信息
            response.sendRedirect(frontEndUrl + "/login?error=oauth_failed");
        }
    }
}