package com.involutionhell.backend.usercenter.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.Test;
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
 */
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
}
