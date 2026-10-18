package com.involutionhell.backend.usercenter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import com.involutionhell.backend.usercenter.oauth.AuthProvider;
import jakarta.servlet.http.Cookie;
import me.zhyd.oauth.enums.AuthResponseStatus;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * OAuthController 集成测试。
 *
 * 设计说明：
 * OAuthController 在内部通过 @Value 属性直接 new AuthGithubRequest()，
 * 无法通过依赖注入替换 JustAuth 的 AuthRequest 实现。
 * 因此本测试仅覆盖以下可验证的行为：
 *   1. renderAuth   —— JustAuth 在本地构建授权 URL，无实际 HTTP 调用，可直接验证 302 重定向。
 *   2. callback 失败路径 —— 携带无效 state/code 时，JustAuth 返回失败响应，
 *                          控制器重定向至前端错误页。
 *
 * callback 成功路径（需要真实 GitHub code + state）超出集成测试范围，
 * 该路径的业务逻辑已由 AuthServiceTests.loginByGithub*() 系列单元测试覆盖。
 * 绑定回调例外：FakeProviderConfig 注册一个不发网络请求的假 provider，端到端走通。
 *
 * 强制 discord 未配置：否则本机 .env 里若有真实 AUTH_DISCORD_ID（被 source 进环境），
 * "未配置 discord → oauth_provider" 这条断言会因 discord 实际已配置而失败。
 */
@TestPropertySource(properties = {
        "justauth.type.discord.client-id=",
        "justauth.type.discord.client-secret="
})
@Import(OAuthControllerIntegrationTests.FakeProviderConfig.class)
class OAuthControllerIntegrationTests extends AbstractWebIntegrationTest {

    // =============================================
    // GET /oauth/render/github — 发起授权跳转
    // =============================================

    @Test
    void renderAuthRedirectsToGitHubAuthorizationUrl() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth/render/github"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location)
                .as("授权重定向地址应指向 GitHub OAuth 授权端点")
                .isNotNull()
                .contains("github.com/login/oauth/authorize")
                // 应携带测试环境配置的 dummy client_id
                .contains("client_id=");
    }

    @Test
    void renderAuthIncludesRedirectUriInAuthorizationUrl() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth/render/github"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        // 授权 URL 必须携带 redirect_uri，否则 GitHub 会拒绝
        assertThat(location)
                .as("授权 URL 必须携带 redirect_uri 参数")
                .contains("redirect_uri");
    }

    @Test
    void renderUnknownProviderRedirectsToProviderError() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth/render/myspace"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl())
                .as("未知 provider 应重定向到 error=oauth_provider，而非 500")
                .isNotNull()
                .endsWith("/login?error=oauth_provider");
    }

    @Test
    void renderUnconfiguredDiscordRedirectsToProviderError() throws Exception {
        // 测试环境未配 AUTH_DISCORD_ID → discord client-id 为空 → 走 oauth_provider 分支
        MvcResult result = mockMvc.perform(get("/oauth/render/discord"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl())
                .as("未配置的 discord 应重定向到 error=oauth_provider，不影响启动")
                .isNotNull()
                .endsWith("/login?error=oauth_provider");
    }

    // =============================================
    // GET /api/auth/callback/github — OAuth 回调
    // =============================================

    @Test
    void renderSetsStateCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/oauth/render/github"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie)
                .as("render 必须种下 httpOnly + SameSite=Lax 的 state cookie")
                .isNotNull()
                .contains("ih_oauth_state=")
                .contains("HttpOnly")
                .contains("SameSite=Lax");
    }

    @Test
    void callbackWithoutStateCookieIsRejectedBeforeTokenExchange() throws Exception {
        // 带 code+state 但无 state cookie（伪造 state / cookie 丢失）→ INV-007 在换 token 前拒绝
        MvcResult result = mockMvc.perform(
                        get("/api/auth/callback/github")
                                .param("code", "invalid-code")
                                .param("state", "invalid-state"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location)
                .as("state 与 cookie 不匹配时应拒绝并重定向到 state 错误页")
                .isNotNull()
                .endsWith("/login?error=oauth_state");
    }

    @Test
    void callbackWithMatchingStateCookieProceedsPastStateCheck() throws Exception {
        // state == cookie，越过 INV-007 校验后进入 JustAuth 换 token；code 无效 → oauth_failed。
        // 关键是它没有停在 oauth_state，证明 cookie 匹配这条正路是通的。
        MvcResult result = mockMvc.perform(
                        get("/api/auth/callback/github")
                                .param("code", "invalid-code")
                                .param("state", "matching-state")
                                .cookie(new jakarta.servlet.http.Cookie("ih_oauth_state", "matching-state")))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location)
                .as("cookie 匹配后应越过 state 校验，止于 JustAuth 换 token 失败")
                .isNotNull()
                .endsWith("/login?error=oauth_failed");
    }

    @Test
    void callbackWithoutParametersRedirectsToFrontendErrorPage() throws Exception {
        // 完全不携带任何参数，模拟用户直接访问回调地址
        MvcResult result = mockMvc.perform(get("/api/auth/callback/github"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location)
                .as("无参数请求时应重定向至前端错误页")
                .isNotNull()
                .endsWith("/login?error=oauth_failed");
    }

    // =============================================
    // GET /oauth/bind/{provider} — 绑定发起（M2b）
    // =============================================

    @Test
    void bindEntryRequiresLogin() throws Exception {
        // INV-007：绑定目标账号只能来自服务端校验过的会话。未登录就发起绑定必须被拒，
        // 否则"绑定到哪个账号"就只能取自客户端可控输入 = 绑定劫持。
        mockMvc.perform(get("/oauth/bind/github"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bindEntryRequiresLoginEvenForUnknownProvider() throws Exception {
        // 鉴权必须先于 provider 解析，否则未登录者能通过响应差异探测支持哪些 provider
        mockMvc.perform(get("/oauth/bind/myspace"))
                .andExpect(status().isUnauthorized());
    }

    // =============================================
    // 绑定回调（M2b）—— 用 FakeProviderConfig 的假 provider 走通换 token
    // =============================================

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanupFakeIdentities() {
        jdbc.update("DELETE FROM user_identities WHERE provider = ?", FAKE);
        // 回归失败时（回调误入登录分支）会建出 fakeoauth_* 账号，别让它污染其它测试
        jdbc.update("DELETE FROM user_accounts WHERE username LIKE ?", FAKE + "_%");
    }

    /** 以 token 身份发起绑定，返回回调要带回的 state + 绑定标记 cookie。 */
    private Cookie[] startBind(String token) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/oauth/bind/" + FAKE).header("satoken", token))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse();
        Cookie state = res.getCookie(OAuthController.STATE_COOKIE);
        Cookie marker = res.getCookie(OAuthController.BIND_COOKIE);
        assertThat(marker).as("发起绑定必须种下绑定标记").isNotNull();
        assertThat(marker.getValue()).isEqualTo("1");
        return new Cookie[]{state, marker};
    }

    private String callback(String code, Cookie[] cookies, String token) throws Exception {
        var req = get("/api/auth/callback/" + FAKE)
                .param("code", code)
                .param("state", cookies[0].getValue())
                .cookie(cookies);
        if (token != null) {
            req.header("satoken", token);
        }
        return mockMvc.perform(req).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    private int accountCount() {
        return jdbc.queryForObject("SELECT count(*) FROM user_accounts", Integer.class);
    }

    private java.util.List<Long> ownersOf(String providerUserId) {
        return jdbc.queryForList("SELECT user_id FROM user_identities WHERE provider = ? AND provider_user_id = ?",
                Long.class, FAKE, providerUserId);
    }

    @Test
    void bindCallbackInSameSessionAttachesIdentityWithoutCreatingAccount() throws Exception {
        String alice = loginAsAlice();
        int before = accountCount();

        String location = callback("same-session", startBind(alice), alice);

        assertThat(location).endsWith("/settings?bind=ok");
        assertThat(ownersOf("same-session")).containsExactly(2L); // alice
        assertThat(accountCount()).as("绑定不建号").isEqualTo(before);
    }

    @Test
    void bindCallbackAfterLogoutIsRejected() throws Exception {
        Cookie[] cookies = startBind(loginAsAlice());

        assertThat(callback("logged-out", cookies, null)).endsWith("/settings?bind_error=bind_session");
        assertThat(ownersOf("logged-out")).isEmpty();
    }

    @Test
    void bindCallbackUnderAnotherUsersSessionIsRejected() throws Exception {
        Cookie[] cookies = startBind(loginAsAlice());

        assertThat(callback("other-user", cookies, loginAsAdmin())).endsWith("/settings?bind_error=bind_session");
        assertThat(ownersOf("other-user")).isEmpty();
    }

    @Test
    void replayedBindCallbackIsRejectedInsteadOfFallingIntoLogin() throws Exception {
        String alice = loginAsAlice();
        Cookie[] cookies = startBind(alice);
        assertThat(callback("replayed", cookies, alice)).endsWith("/settings?bind=ok");
        int before = accountCount();

        // 意图已被一次性消费。没有绑定标记的话，这次会被当成登录：按假身份登录/建号。
        assertThat(callback("replayed", cookies, alice)).endsWith("/settings?bind_error=bind_session");
        // 换个 code 也一样——不能让"丢了意图"的回调建号
        assertThat(callback("replayed-new", cookies, null)).endsWith("/settings?bind_error=bind_session");

        assertThat(accountCount()).isEqualTo(before);
        assertThat(ownersOf("replayed")).containsExactly(2L);
        assertThat(ownersOf("replayed-new")).isEmpty();
    }

    @Test
    void loginRenderClearsStaleBindMarker() throws Exception {
        // 放弃绑定后再走登录：残留标记必须被清掉，否则登录回调会被误判成绑定
        MockHttpServletResponse res = mockMvc.perform(get("/oauth/render/github"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse();
        Cookie marker = res.getCookie(OAuthController.BIND_COOKIE);
        assertThat(marker).isNotNull();
        assertThat(marker.getMaxAge()).isZero();
    }

    static final String FAKE = "fakeoauth";

    /**
     * 假 provider：authorize 不发请求，login 把 code 当作第三方 uuid 返回。
     * 每个测试用不同 code，互不撞 UNIQUE(provider, provider_user_id)。
     */
    @TestConfiguration
    static class FakeProviderConfig {
        @Bean
        AuthProvider fakeOAuthProvider() {
            return new AuthProvider() {
                @Override
                public String key() {
                    return FAKE;
                }

                @Override
                public String redirectUri() {
                    return "https://example.com/api/auth/callback/" + FAKE;
                }

                @Override
                public boolean isEmailVerified(AuthUser user) {
                    return false;
                }

                @Override
                public AuthRequest newRequest() {
                    return new AuthRequest() {
                        @Override
                        public String authorize(String state) {
                            return "https://fake.example/authorize?state=" + state;
                        }

                        @Override
                        public AuthResponse login(AuthCallback cb) {
                            return new AuthResponse<>(AuthResponseStatus.SUCCESS.getCode(), null,
                                    AuthUser.builder().uuid(cb.getCode()).nickname("fake-" + cb.getCode()).build());
                        }
                    };
                }
            };
        }
    }
}
