package com.involutionhell.backend.usercenter.controller;

import static com.involutionhell.backend.usercenter.controller.OAuthControllerIntegrationTests.FAKE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.common.email.ResendEmailService;
import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Import(OAuthControllerIntegrationTests.FakeProviderConfig.class)
class RegistrationFlowIntegrationTests extends AbstractWebIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private ResendEmailService email;

    @BeforeEach
    void resendOk() {
        when(email.isConfigured()).thenReturn(true);
        when(email.sendHtml(anyString(), anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM user_identities WHERE provider = ?", FAKE);
        jdbc.update("DELETE FROM user_accounts WHERE username LIKE ?", FAKE + "_%");
        jdbc.update("UPDATE user_accounts SET email = NULL WHERE username = 'alice'");
    }

    private int accounts() {
        return jdbc.queryForObject("SELECT count(*) FROM user_accounts", Integer.class);
    }

    /** 走一次假 provider 的登录回调，返回 response（重定向地址 + cookie）。 */
    private MockHttpServletResponse oauthLogin(String uuid) throws Exception {
        Cookie state = mockMvc.perform(get("/oauth/render/" + FAKE)).andReturn().getResponse()
                .getCookie(OAuthController.STATE_COOKIE);
        return mockMvc.perform(get("/api/auth/callback/" + FAKE).param("code", uuid)
                        .param("state", state.getValue()).cookie(state))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
    }

    private String sendAndCaptureCode(Cookie reg, String to) throws Exception {
        mockMvc.perform(post("/auth/register/send-otp").cookie(reg)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + to + "\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        Mockito.verify(email, Mockito.atLeastOnce()).sendHtml(eq(to), anyString(), html.capture());
        Matcher m = Pattern.compile("(\\d{6})").matcher(html.getValue());
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    private org.springframework.test.web.servlet.ResultActions verify(Cookie reg, String to, String code) throws Exception {
        return mockMvc.perform(post("/auth/register/verify").cookie(reg).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + to + "\",\"code\":\"" + code + "\"}"));
    }

    @Test
    void newUserIsSentToVerifyPageWithPendingIdOnlyInHttpOnlyCookie() throws Exception {
        int before = accounts();
        MockHttpServletResponse res = oauthLogin("new-1");

        assertThat(res.getRedirectedUrl()).endsWith("/login/verify"); // 不带 pendingId、不带邮箱
        Cookie reg = res.getCookie(OAuthController.REG_COOKIE);
        assertThat(reg).isNotNull();
        assertThat(reg.isHttpOnly()).isTrue();
        assertThat(accounts()).as("验证前不建号").isEqualTo(before);
    }

    @Test
    void fullRegistrationCreatesAccountWithVerifiedEmailAndLogsIn() throws Exception {
        int before = accounts();
        Cookie reg = oauthLogin("new-2").getCookie(OAuthController.REG_COOKIE);
        mockMvc.perform(get("/auth/register/pending").cookie(reg))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.provider").value(FAKE));

        String code = sendAndCaptureCode(reg, "me@example.com");
        String body = verify(reg, "me@example.com", code).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.data.tokenValue");

        mockMvc.perform(get("/auth/me").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("me@example.com"))
                .andExpect(jsonPath("$.data.username").value(FAKE + "_new-2"));
        assertThat(accounts()).isEqualTo(before + 1);
        assertThat(jdbc.queryForMap("SELECT roles, permissions FROM user_accounts WHERE username = ?", FAKE + "_new-2"))
                .as("新账号只有 user 角色、没有任何额外权限（INV-012）")
                .containsEntry("roles", "user").containsEntry("permissions", "");

        // 回访：身份已在，直接发 token，不再进验证页、不再建号
        assertThat(oauthLogin("new-2").getRedirectedUrl()).contains("/#token=");
        assertThat(accounts()).isEqualTo(before + 1);
        // 一次性：同一 pendingId 不能再建第二个号
        verify(reg, "me@example.com", code).andExpect(status().isGone());
    }

    @Test
    void verifiedEmailOfExistingAccountIsNotMergedAndCreatesNothing() throws Exception {
        jdbc.update("UPDATE user_accounts SET email = 'alice@example.com' WHERE username = 'alice'");
        int before = accounts();
        Cookie reg = oauthLogin("new-3").getCookie(OAuthController.REG_COOKIE);

        String code = sendAndCaptureCode(reg, "Alice@Example.com");
        verify(reg, "Alice@Example.com", code).andExpect(status().isConflict());

        assertThat(accounts()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_identities WHERE provider = ?", Integer.class, FAKE))
                .as("不挂到 alice 名下，也不建新号").isZero();
    }

    @Test
    void wrongCodeIs400UntilLockThen410() throws Exception {
        Cookie reg = oauthLogin("new-4").getCookie(OAuthController.REG_COOKIE);
        sendAndCaptureCode(reg, "x@example.com");
        for (int i = 0; i < 4; i++) {
            verify(reg, "x@example.com", "000000").andExpect(status().isBadRequest());
        }
        verify(reg, "x@example.com", "000000").andExpect(status().isGone());
    }

    @Test
    void endpointsArePublicButNeedTheCookie() throws Exception {
        mockMvc.perform(get("/auth/register/pending")).andExpect(status().isGone());
        mockMvc.perform(post("/auth/register/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@b.co\"}")).andExpect(status().isGone());
        verify(new Cookie(OAuthController.REG_COOKIE, "forged"), "a@b.co", "123456").andExpect(status().isGone());
    }
}
