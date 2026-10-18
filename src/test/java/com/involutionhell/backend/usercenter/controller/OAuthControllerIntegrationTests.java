package com.involutionhell.backend.usercenter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import com.involutionhell.backend.usercenter.oauth.AuthProvider;
import com.involutionhell.backend.usercenter.service.AuthService;
import com.involutionhell.backend.usercenter.service.UserIdentityService;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
 * 真实 GitHub 的 callback 成功路径（需要真实 code + state）超出集成测试范围；
 * FakeProviderConfig 注册两个不发网络请求的假 provider，登录/绑定回调的成功路径用它们端到端走通。
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
    // POST /oauth/bind/{provider} — 绑定发起（M2b）
    // =============================================

    @Test
    void bindEntryRequiresLogin() throws Exception {
        // INV-007：绑定目标账号只能来自服务端校验过的会话。未登录就发起绑定必须被拒，
        // 否则"绑定到哪个账号"就只能取自客户端可控输入 = 绑定劫持。
        mockMvc.perform(post("/oauth/bind/github"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bindEntryRequiresLoginEvenForUnknownProvider() throws Exception {
        // 鉴权必须先于 provider 解析，否则未登录者能通过响应差异探测支持哪些 provider
        mockMvc.perform(post("/oauth/bind/myspace"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bindStartIsNotAcceptedViaGet() throws Exception {
        // GET 能被跨站顶级导航触发，带上浏览器里被种下的别人的 satoken cookie 就能发起绑定
        MockHttpServletResponse res = mockMvc.perform(get("/oauth/bind/" + FAKE).header("satoken", loginAsAlice()))
                .andExpect(status().isMethodNotAllowed())
                .andReturn().getResponse();
        assertThat(res.getCookie(OAuthController.STATE_COOKIE)).isNull();
        assertThat(res.getCookie(OAuthController.BIND_COOKIE)).isNull();
    }

    @Test
    void bindStartReturnsAuthorizeUrlAndSetsStateAndMarkerCookies() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/oauth/bind/" + FAKE).header("satoken", loginAsAlice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse();
        String url = JsonPath.read(res.getContentAsString(), "$.data.url");
        Cookie state = res.getCookie(OAuthController.STATE_COOKIE);
        Cookie marker = res.getCookie(OAuthController.BIND_COOKIE);

        assertThat(url).isEqualTo("https://fake.example/authorize?state=" + state.getValue());
        assertThat(state.isHttpOnly()).isTrue();
        assertThat(state.getMaxAge()).isEqualTo(300);
        assertThat(marker.getValue()).isEqualTo("1");
        assertThat(marker.getMaxAge()).as("标记比 state 活得久，过期的绑定才能回到设置页")
                .isEqualTo(OAuthController.BIND_COOKIE_MAX_AGE_SECONDS);
    }

    @Test
    void bindStartWithUnknownProviderIsJson4xxAndSetsNoCookies() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/oauth/bind/myspace").header("satoken", loginAsAlice()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andReturn().getResponse();
        assertThat(res.getCookie(OAuthController.STATE_COOKIE)).isNull();
        assertThat(res.getCookie(OAuthController.BIND_COOKIE)).isNull();
    }

    // =============================================
    // 绑定回调（M2b）—— 用 FakeProviderConfig 的假 provider 走通换 token
    // =============================================

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void cleanupFakeIdentities() {
        jdbc.update("DELETE FROM user_identities WHERE provider IN (?, ?)", FAKE, FAKE_GH);
        // 回归失败时（回调误入登录分支）会建出 fakeoauth_* 账号，别让它污染其它测试
        jdbc.update("DELETE FROM user_accounts WHERE username LIKE ? OR username LIKE ?", FAKE + "_%", FAKE_GH + "_%");
        jdbc.update("UPDATE user_accounts SET email = NULL WHERE username = 'alice'");
    }

    /** 以 token 身份发起绑定，返回回调要带回的 state + 绑定标记 cookie。 */
    private Cookie[] startBind(String token) throws Exception {
        return startBind(FAKE, token);
    }

    private Cookie[] startBind(String provider, String token) throws Exception {
        MockHttpServletResponse res = mockMvc.perform(post("/oauth/bind/" + provider).header("satoken", token))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        Cookie state = res.getCookie(OAuthController.STATE_COOKIE);
        Cookie marker = res.getCookie(OAuthController.BIND_COOKIE);
        assertThat(marker).as("发起绑定必须种下绑定标记").isNotNull();
        assertThat(marker.getValue()).isEqualTo("1");
        return new Cookie[]{state, marker};
    }

    private String callback(String code, Cookie[] cookies, String token) throws Exception {
        return callback(FAKE, code, cookies, token);
    }

    private String callback(String provider, String code, Cookie[] cookies, String token) throws Exception {
        var req = get("/api/auth/callback/" + provider)
                .param("code", code)
                .param("state", cookies[0].getValue())
                .cookie(cookies);
        if (token != null) {
            req.header("satoken", token);
        }
        return mockMvc.perform(req).andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
    }

    /** 走一次登录（非绑定）：render 拿 state cookie → 回调。返回重定向地址。 */
    private String login(String provider, String code) throws Exception {
        return login(provider, code, null).getRedirectedUrl();
    }

    /** 同上，render 带 ?n=（null 即不带），把 render 种下的 cookie 全数带回回调。 */
    private MockHttpServletResponse login(String provider, String code, String n) throws Exception {
        var render = get("/oauth/render/" + provider);
        if (n != null) {
            render.param("n", n);
        }
        // 浏览器不会回传 Max-Age=0 的清除型 cookie
        Cookie[] cookies = java.util.Arrays.stream(mockMvc.perform(render).andReturn().getResponse().getCookies())
                .filter(c -> c.getMaxAge() != 0).toArray(Cookie[]::new);
        Cookie state = java.util.Arrays.stream(cookies)
                .filter(c -> c.getName().equals(OAuthController.STATE_COOKIE)).findFirst().orElseThrow();
        return mockMvc.perform(get("/api/auth/callback/" + provider)
                        .param("code", code).param("state", state.getValue()).cookie(cookies))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse();
    }

    private int accountCount() {
        return jdbc.queryForObject("SELECT count(*) FROM user_accounts", Integer.class);
    }

    private java.util.List<Long> ownersOf(String providerUserId) {
        return ownersOf(FAKE, providerUserId);
    }

    private java.util.List<Long> ownersOf(String provider, String providerUserId) {
        return jdbc.queryForList("SELECT user_id FROM user_identities WHERE provider = ? AND provider_user_id = ?",
                Long.class, provider, providerUserId);
    }

    /** 建一个账号（密码同 alice），可选挂若干身份 {provider, providerUserId}。 */
    private long account(String username, String[]... identities) {
        jdbc.update("""
                INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions)
                SELECT ?, password_hash, ?, TRUE, 'user', '' FROM user_accounts WHERE username = 'alice'""",
                username, username);
        long id = jdbc.queryForObject("SELECT id FROM user_accounts WHERE username = ?", Long.class, username);
        for (String[] i : identities) {
            jdbc.update("INSERT INTO user_identities (user_id, provider, provider_user_id) VALUES (?, ?, ?)", id, i[0], i[1]);
        }
        return id;
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
    void bindThatOutlivesStateCookieReturnsToSettingsNotLogin() throws Exception {
        // 在 provider 页停了 5 分钟以上：意图和 state cookie 都过期了，只剩标记。
        // 必须回到设置页报绑定错误，而不是 /login（登录页再点一次就是分叉出新账号）。
        String alice = loginAsAlice();
        Cookie marker = startBind(alice)[1];
        String location = mockMvc.perform(get("/api/auth/callback/" + FAKE)
                        .param("code", "late").param("state", "expired-state")
                        .cookie(marker).header("satoken", alice))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).endsWith("/settings?bind_error=oauth_state");
        assertThat(ownersOf("late")).isEmpty();
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

    // =============================================
    // 登录 nonce 回显（INV-007）：#token= 只回给发起登录的那个 tab
    // =============================================

    private static final String NONCE = "AbCdEfGhIjKlMnOp_-0123";

    @Test
    void renderWithValidNonceSetsLoginNonceCookie() throws Exception {
        MockHttpServletResponse res = mockMvc.perform(get("/oauth/render/" + FAKE).param("n", NONCE))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse();
        Cookie nonce = res.getCookie(OAuthController.LOGIN_NONCE_COOKIE);
        assertThat(nonce).isNotNull();
        assertThat(nonce.getValue()).isEqualTo(NONCE);
        assertThat(nonce.isHttpOnly()).isTrue();
        assertThat(nonce.getMaxAge()).isEqualTo(300);
        assertThat(nonce.getPath()).isEqualTo("/");
        assertThat(res.getHeaders("Set-Cookie"))
                .anySatisfy(h -> assertThat(h).startsWith(OAuthController.LOGIN_NONCE_COOKIE + "=").contains("SameSite=Lax"));
    }

    @Test
    void renderWithInvalidOrMissingNonceSetsNone() throws Exception {
        for (String n : new String[]{"short", "a".repeat(65), "has space in it!!", "AbCdEfGh;Path=/x;"}) {
            assertThat(mockMvc.perform(get("/oauth/render/" + FAKE).param("n", n)).andReturn().getResponse()
                    .getCookie(OAuthController.LOGIN_NONCE_COOKIE)).as(n).isNull();
        }
        assertThat(mockMvc.perform(get("/oauth/render/" + FAKE)).andReturn().getResponse()
                .getCookie(OAuthController.LOGIN_NONCE_COOKIE)).isNull();
    }

    @Test
    void loginSuccessEchoesNonceFromCookieAndClearsIt() throws Exception {
        account(FAKE + "_n1", new String[]{FAKE, "n1"});
        MockHttpServletResponse res = login(FAKE, "n1", NONCE);

        assertThat(res.getRedirectedUrl()).matches(".*/#token=[^&]+&n=" + NONCE);
        assertThat(res.getCookie(OAuthController.LOGIN_NONCE_COOKIE).getMaxAge()).isZero();
    }

    @Test
    void loginSuccessWithoutNonceCookieRedirectsWithPlainToken() throws Exception {
        account(FAKE + "_n2", new String[]{FAKE, "n2"});

        assertThat(login(FAKE, "n2", null).getRedirectedUrl()).matches(".*/#token=[^&]+");
    }

    @Test
    void nonceCookieIsClearedOnErrorAndRegistrationRedirects() throws Exception {
        MockHttpServletResponse reg = login(FAKE, "n-new", NONCE);
        assertThat(reg.getRedirectedUrl()).as("注册跳转不带 n").endsWith("/login/verify");
        assertThat(reg.getCookie(OAuthController.LOGIN_NONCE_COOKIE).getMaxAge()).isZero();

        MockHttpServletResponse err = mockMvc.perform(get("/api/auth/callback/" + FAKE)
                        .cookie(new Cookie(OAuthController.LOGIN_NONCE_COOKIE, NONCE)))
                .andReturn().getResponse();
        assertThat(err.getRedirectedUrl()).endsWith("/login?error=oauth_failed");
        assertThat(err.getCookie(OAuthController.LOGIN_NONCE_COOKIE).getMaxAge()).isZero();
    }

    // =============================================
    // 账号归属：未验证邮箱、自动挂靠、解绑/换绑之后的登录
    // =============================================

    private String emailOf(String username) {
        return jdbc.queryForObject("SELECT email FROM user_accounts WHERE username = ?", String.class, username);
    }

    @Test
    void unverifiedEmailPlantedViaBindNeverCapturesAVictimsFirstLogin() throws Exception {
        // 攻击者（email 为空）绑一个报了受害者邮箱、但没验证过的第三方号，再用它登录
        String attacker = loginAsAlice();
        assertThat(callback("evil|victim@x.com|false", startBind(attacker), attacker)).endsWith("/settings?bind=ok");
        assertThat(login(FAKE, "evil|victim@x.com|false")).contains("/#token=");
        assertThat(emailOf("alice")).as("未验证邮箱不能进 user_accounts.email（它是自动挂靠的锚点）").isNull();

        // 受害者第一次用另一家登录，已验证邮箱就是 victim@x.com：不能落进攻击者账号
        assertThat(login(FAKE_GH, "victim-gh|victim@x.com|true")).endsWith("/login/verify");
        assertThat(ownersOf(FAKE_GH, "victim-gh")).isEmpty();
    }

    @Test
    void verifiedEmailNeverAutoLinksIntoAccountThatAlreadyHasThatProvider() throws Exception {
        jdbc.update("UPDATE user_accounts SET email = 'shared@x.com' WHERE username = 'alice'");
        String alice = loginAsAlice();
        assertThat(callback(FAKE_GH, "gh-1", startBind(FAKE_GH, alice), alice)).endsWith("/settings?bind=ok");

        // 同家第二个号报了同一个已验证邮箱：alice 已有这家身份，挂不上，进注册
        assertThat(login(FAKE_GH, "gh-2|shared@x.com|true")).endsWith("/login/verify");
        assertThat(ownersOf(FAKE_GH, "gh-2")).isEmpty();
        // 反向：alice 还没有的那家，同一个已验证邮箱照常挂靠
        assertThat(login(FAKE, "dc-1|shared@x.com|true")).contains("/#token=");
        assertThat(ownersOf(FAKE, "dc-1")).containsExactly(2L);
    }

    @Test
    void unboundIdentityCannotLogBackInThroughTheUsername() throws Exception {
        account(FAKE_GH + "_g1", new String[]{FAKE_GH, "g1"}, new String[]{FAKE, "d1"});
        String token = loginAndGetToken(FAKE_GH + "_g1", "Alice@123456");
        mockMvc.perform(delete("/api/user-center/identities/" + FAKE_GH).header("satoken", token))
                .andExpect(status().isOk());

        assertThat(login(FAKE_GH, "g1")).endsWith("/login/verify");
        assertThat(ownersOf(FAKE_GH, "g1")).as("解绑不能被下一次登录静默补回来").isEmpty();
    }

    @Test
    void switchingGithubViaBindLocksTheOldOneOut() throws Exception {
        long id = account(FAKE_GH + "_g1", new String[]{FAKE_GH, "g1"}, new String[]{FAKE, "d1"});
        String token = loginAndGetToken(FAKE_GH + "_g1", "Alice@123456");
        mockMvc.perform(delete("/api/user-center/identities/" + FAKE_GH).header("satoken", token))
                .andExpect(status().isOk());
        assertThat(callback(FAKE_GH, "g2", startBind(FAKE_GH, token), token)).endsWith("/settings?bind=ok");

        assertThat(login(FAKE_GH, "g1")).endsWith("/login/verify");
        assertThat(login(FAKE_GH, "g2")).contains("/#token=");
        assertThat(ownersOf(FAKE_GH, "g2")).containsExactly(id);
        assertThat(ownersOf(FAKE_GH, "g1")).isEmpty();
    }

    @Test
    void legacyAccountWithoutAnyIdentityStillLogsInByUsername() throws Exception {
        // M0 回填之前的老账号（一行身份都没有）：用户名回退照旧，并补上身份行
        long id = account(FAKE_GH + "_g9");
        assertThat(login(FAKE_GH, "g9")).contains("/#token=");
        assertThat(ownersOf(FAKE_GH, "g9")).containsExactly(id);
    }

    // =============================================
    // 登录回调兜底：账号禁用 / 意外异常不能是裸 500
    // =============================================

    @MockitoSpyBean
    private AuthService authService;

    @Test
    void disabledAccountIsToldSoInsteadOfOauthFailed() throws Exception {
        long id = account(FAKE + "_dis", new String[]{FAKE, "dis"});
        jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", id);

        assertThat(login(FAKE, "dis")).endsWith("/login?error=account_disabled");
    }

    @Test
    void unexpectedLoginFailureRedirectsToOauthFailed() throws Exception {
        doThrow(new RuntimeException("db down")).when(authService).loginByProvider(eq(FAKE), any());

        assertThat(login(FAKE, "boom")).endsWith("/login?error=oauth_failed");
    }

    @MockitoSpyBean
    private UserIdentityService userIdentityService;

    @Test
    void unexpectedBindFailureRedirectsToSettingsInsteadOfRaw500() throws Exception {
        String alice = loginAsAlice();
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(userIdentityService).bind(anyLong(), eq(FAKE), eq("bind-boom"), any(), any());

        assertThat(callback("bind-boom", startBind(alice), alice)).endsWith("/settings?bind_error=oauth_failed");
        assertThat(ownersOf("bind-boom")).isEmpty();
    }

    static final String FAKE = "fakeoauth";
    /** 第二个假 provider：测"同家第二个号""另一家登录"这类需要两家的场景。 */
    static final String FAKE_GH = "fakegh";

    /**
     * 假 provider：authorize 不发请求，login 把 code 解析成第三方用户返回。
     * code 形如 "uuid" 或 "uuid|email|verified"（verified 即该 provider 是否担保这个邮箱）。
     * 每个测试用不同 uuid，互不撞 UNIQUE(provider, provider_user_id)。
     */
    @TestConfiguration
    static class FakeProviderConfig {
        @Bean
        AuthProvider fakeOAuthProvider() {
            return fake(FAKE);
        }

        @Bean
        AuthProvider fakeGithubProvider() {
            return fake(FAKE_GH);
        }

        private static AuthProvider fake(String key) {
            return new AuthProvider() {
                @Override
                public String key() {
                    return key;
                }

                @Override
                public String redirectUri() {
                    return "https://example.com/api/auth/callback/" + key;
                }

                @Override
                public boolean isEmailVerified(AuthUser user) {
                    return user.getRawUserInfo().getBooleanValue("verified");
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
                            String[] parts = cb.getCode().split("\\|");
                            com.alibaba.fastjson.JSONObject raw = new com.alibaba.fastjson.JSONObject();
                            raw.put("verified", parts.length > 2 && Boolean.parseBoolean(parts[2]));
                            return new AuthResponse<>(AuthResponseStatus.SUCCESS.getCode(), null,
                                    AuthUser.builder().uuid(parts[0]).nickname("fake-" + parts[0])
                                            .email(parts.length > 1 ? parts[1] : null).rawUserInfo(raw).build());
                        }
                    };
                }
            };
        }
    }
}
