package com.involutionhell.backend.sso.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.sso.config.SsoProperties;
import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import com.involutionhell.backend.usercenter.service.PasswordService;
import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * IH 通行证（INV-010）端到端：真登录拿 satoken → POST /oauth/sso/code 签码 →
 * POST /internal/sso/token 换资料。码 60 秒过期靠假时钟，在 SsoServiceTests 里测。
 */
@TestPropertySource(properties = {
        "sso.clients.holocard.secret=" + SsoControllerIntegrationTests.SECRET,
        "sso.clients.holocard.redirect-uri=" + SsoControllerIntegrationTests.REDIRECT,
        // 第二个启用的 client：说明 client 表不是写死的，也用来测"拿别家的码来换"
        "sso.clients.other.secret=other-secret",
        "sso.clients.other.redirect-uri=https://other.example/cb",
        // 登记了但 secret 为空——生产上漏配 SSO_HOLOCARD_SECRET 时 holocard 就是这个状态
        "sso.clients.dormant.secret=",
        "sso.clients.dormant.redirect-uri=https://dormant.example/cb"
})
class SsoControllerIntegrationTests extends AbstractWebIntegrationTest {

    static final String SECRET = "test-holocard-secret";
    static final String REDIRECT = "https://holocard.longsizhuo.com/auth/callback";
    private static final String PASSWORD = "Sso@123456";
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordService passwordService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private SsoProperties ssoProperties;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM user_accounts WHERE username LIKE 'sso-it-%'");
    }

    // ── 成功路径 ─────────────────────────────────────────────────────────────

    @Test
    void seedAccountCanSignInAndGetsOnlyTheMinimalProfile() throws Exception {
        String token = loginAsAlice();
        long aliceId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE username = 'alice'", Long.class);
        String verifier = newVerifier();
        Map<String, Object> body = codeBody(verifier);
        String state = "a b&c=d/é+?";   // 回跳时必须 URL 编码
        body.put("state", state);

        String redirect = JsonPath.read(postCode(token, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ok"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.redirect");
        String code = codeIn(redirect);
        assertThat(code).matches("[A-Za-z0-9_-]{43}");
        assertThat(redirect).isEqualTo(
                REDIRECT + "?code=" + code + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8));

        String json = exchange(code, verifier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.sub").value(String.valueOf(aliceId)))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.displayName").value("Alice"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> data = JsonPath.read(json, "$.data");
        assertThat(data.keySet()).isSubsetOf("sub", "username", "displayName", "avatarUrl");
        assertThat(json).doesNotContain(token);
    }

    @Test
    void profileNeverCarriesEmailRolesPermissionsOrToken() throws Exception {
        String username = newUsername();
        long id = createUser(username);
        String token = loginAndGetToken(username, PASSWORD);
        String verifier = newVerifier();

        String json = exchange(issueCode(token, verifier), verifier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sub").value(String.valueOf(id)))
                .andExpect(jsonPath("$.data.username").value(username))
                .andExpect(jsonPath("$.data.displayName").value("SSO Tester"))
                .andExpect(jsonPath("$.data.avatarUrl").value("https://avatars.example/sso.png"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> data = JsonPath.read(json, "$.data");
        assertThat(data).containsOnlyKeys("sub", "username", "displayName", "avatarUrl");
        assertThat(json).doesNotContain("sso-tester@example.com", "admin", "user:center:manage", token);
    }

    // ── 签码 /oauth/sso/code ─────────────────────────────────────────────────

    @Test
    void issuingCodeRequiresLogin() throws Exception {
        postCode(null, codeBody(newVerifier()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("未提供 Token"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            REDIRECT + "/",                                                // 多一个斜杠
            REDIRECT + "?next=/x",                                         // 加 query
            REDIRECT + "#x",
            "https://HOLOCARD.longsizhuo.com/auth/callback",               // 换大小写
            "https://holocard.longsizhuo.com/auth/Callback",
            "http://holocard.longsizhuo.com/auth/callback",                // 换 scheme
            "https://holocard.longsizhuo.com.evil.example/auth/callback",  // 前缀一样的别人家
            "https://holocard.longsizhuo.com/auth/callback/../../evil",
            "https://other.example/cb",                                    // 别的 client 登记的地址
            ""
    })
    @NullSource
    void redirectUriMustMatchTheRegisteredValueExactly(String redirectUri) throws Exception {
        Map<String, Object> body = codeBody(newVerifier());
        body.put("redirectUri", redirectUri);
        postCode(loginAsAlice(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.redirect").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = {"nope", "HOLOCARD", "holocard ", ""})
    @NullSource
    void unknownClientCannotGetCode(String clientId) throws Exception {
        Map<String, Object> body = codeBody(newVerifier());
        body.put("clientId", clientId);
        postCode(loginAsAlice(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.redirect").doesNotExist());
    }

    static Stream<Arguments> malformedCodeRequests() {
        return Stream.of(
                Arguments.of("codeChallenge", "A".repeat(42)),         // 短一位
                Arguments.of("codeChallenge", "A".repeat(44)),         // 长一位
                Arguments.of("codeChallenge", "A".repeat(42) + "="),   // 带填充
                Arguments.of("codeChallenge", "A".repeat(42) + "+"),   // 标准 base64 而不是 base64url
                Arguments.of("codeChallenge", "A".repeat(43) + "\n"),  // 尾随换行（Java 的 $ 会放过）
                Arguments.of("codeChallenge", null),
                Arguments.of("codeChallengeMethod", "plain"),
                Arguments.of("codeChallengeMethod", "s256"),
                Arguments.of("codeChallengeMethod", null),
                Arguments.of("state", ""),
                Arguments.of("state", "s".repeat(513)),
                Arguments.of("state", null));
    }

    @ParameterizedTest
    @MethodSource("malformedCodeRequests")
    void malformedPkceOrStateIsRejected(String field, String value) throws Exception {
        Map<String, Object> body = codeBody(newVerifier());
        body.put(field, value);
        postCode(loginAsAlice(), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.redirect").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 512})
    void stateLengthBoundsAreInclusive(int length) throws Exception {
        Map<String, Object> body = codeBody(newVerifier());
        body.put("state", "s".repeat(length));
        postCode(loginAsAlice(), body).andExpect(status().isOk());
    }

    @Test
    void disabledAccountCannotGetCode() throws Exception {
        String username = newUsername();
        long id = createUser(username);
        String token = loginAndGetToken(username, PASSWORD);
        disable(id);

        postCode(token, codeBody(newVerifier()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ── client 未启用 ────────────────────────────────────────────────────────

    @Test
    void clientWithEmptySecretIsRejectedByBothEndpoints() throws Exception {
        // 前提：dormant 确实进了 client 表、只是没 secret；否则这条测的只是"未知 client"
        assertThat(ssoProperties.clients()).containsKey("dormant");
        assertThat(ssoProperties.clients().get("dormant").enabled()).isFalse();

        Map<String, Object> body = codeBody(newVerifier());
        body.put("clientId", "dormant");
        body.put("redirectUri", "https://dormant.example/cb");
        postCode(loginAsAlice(), body).andExpect(status().isBadRequest());

        // 空 secret 对空 secret 用 MessageDigest.isEqual 比是相等的，必须在比较前就按未启用拒掉
        for (String secret : new String[] {"", "anything"}) {
            exchange("dormant", secret, "A".repeat(43), newVerifier(), "https://dormant.example/cb")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("invalid_client"));
        }
    }

    // ── 换码 /internal/sso/token ─────────────────────────────────────────────

    @Test
    void wrongSecretIsInvalidClientAndDoesNotBurnTheCode() throws Exception {
        String verifier = newVerifier();
        String code = issueCode(loginAsAlice(), verifier);

        exchange("holocard", "wrong-secret", code, verifier, REDIRECT)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("invalid_client"))
                .andExpect(jsonPath("$.data").doesNotExist());
        exchange("holocard", null, code, verifier, REDIRECT)
                .andExpect(status().isUnauthorized());
        exchange("nope", SECRET, code, verifier, REDIRECT)
                .andExpect(status().isUnauthorized());

        // 没过 client 鉴权的请求碰不到码：拿不到 secret 的人烧不掉别人的码
        exchange(code, verifier).andExpect(status().isOk());
    }

    @Test
    void codeCanOnlyBeRedeemedOnce() throws Exception {
        String verifier = newVerifier();
        String code = issueCode(loginAsAlice(), verifier);

        exchange(code, verifier).andExpect(status().isOk());
        exchange(code, verifier)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("invalid_grant"));
    }

    @Test
    void wrongVerifierBurnsTheCode() throws Exception {
        String verifier = newVerifier();
        String code = issueCode(loginAsAlice(), verifier);

        exchange(code, newVerifier())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_grant"));
        // 码已作废，换回正确的 verifier 也不行
        exchange(code, verifier)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_grant"));
    }

    @Test
    void verifierFormatIsEnforcedEvenWhenItsHashMatches() throws Exception {
        // challenge 由一个过短的 verifier 算出：challenge 本身合法，签码能过；换码必须拒
        String shortVerifier = "too-short";
        Map<String, Object> body = codeBody(newVerifier());
        body.put("codeChallenge", challengeOf(shortVerifier));
        String code = codeIn(JsonPath.read(postCode(loginAsAlice(), body)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.redirect"));

        exchange(code, shortVerifier)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_grant"));
    }

    @Test
    void redirectUriAtRedemptionMustMatchTheCode() throws Exception {
        String verifier = newVerifier();
        String code = issueCode(loginAsAlice(), verifier);

        exchange("holocard", SECRET, code, verifier, REDIRECT + "/")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_grant"));
        exchange(code, verifier).andExpect(status().isBadRequest());   // 码已作废
    }

    @Test
    void anotherClientCannotRedeemTheCode() throws Exception {
        String verifier = newVerifier();
        String code = issueCode(loginAsAlice(), verifier);

        // other 的 secret 是对的，但码是签给 holocard 的
        exchange("other", "other-secret", code, verifier, REDIRECT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_grant"));
        exchange(code, verifier).andExpect(status().isBadRequest());   // 码已作废
    }

    @Test
    void accountDisabledAfterCodeWasIssuedCannotRedeem() throws Exception {
        String username = newUsername();
        long id = createUser(username);
        String verifier = newVerifier();
        String code = issueCode(loginAndGetToken(username, PASSWORD), verifier);
        disable(id);

        exchange(code, verifier)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid_grant"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** 和 HoloCard 一样：32 字节随机数 → base64url（43 位）。 */
    private static String newVerifier() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    private static String challengeOf(String verifier) throws Exception {
        return BASE64URL.encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    /** 一份合法的签码请求体，用例再按需改某个字段（HashMap 允许放 null）。 */
    private static Map<String, Object> codeBody(String verifier) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("clientId", "holocard");
        body.put("redirectUri", REDIRECT);
        body.put("state", "state-" + UUID.randomUUID());
        body.put("codeChallenge", challengeOf(verifier));
        body.put("codeChallengeMethod", "S256");
        return body;
    }

    private ResultActions postCode(String token, Map<String, Object> body) throws Exception {
        MockHttpServletRequestBuilder request = post("/oauth/sso/code")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (token != null) {
            request.header("satoken", token);
        }
        return mockMvc.perform(request);
    }

    /** 合法签码，返回 code。 */
    private String issueCode(String token, String verifier) throws Exception {
        String json = postCode(token, codeBody(verifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return codeIn(JsonPath.read(json, "$.data.redirect"));
    }

    private static String codeIn(String redirect) {
        return UriComponentsBuilder.fromUriString(redirect).build().getQueryParams().getFirst("code");
    }

    /** 以 holocard 的身份正常换码。 */
    private ResultActions exchange(String code, String verifier) throws Exception {
        return exchange("holocard", SECRET, code, verifier, REDIRECT);
    }

    private ResultActions exchange(String clientId, String clientSecret, String code, String verifier,
                                   String redirectUri) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("clientId", clientId);
        body.put("clientSecret", clientSecret);
        body.put("code", code);
        body.put("codeVerifier", verifier);
        body.put("redirectUri", redirectUri);
        return mockMvc.perform(post("/internal/sso/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static String newUsername() {
        return "sso-it-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** 能用口令登录的账号，带 email、头像、角色和权限——用来证明这些不会交给 client。 */
    private long createUser(String username) {
        jdbc.update("INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions,"
                        + " avatar_url, email) VALUES (?, ?, 'SSO Tester', TRUE, 'admin', 'user:center:manage', ?, ?)",
                username, passwordService.hash(PASSWORD), "https://avatars.example/sso.png", "sso-tester@example.com");
        return jdbc.queryForObject("SELECT id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private void disable(long userId) {
        jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", userId);
    }
}
