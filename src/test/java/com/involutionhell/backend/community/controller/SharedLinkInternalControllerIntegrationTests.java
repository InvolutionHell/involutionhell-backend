package com.involutionhell.backend.community.controller;

import com.involutionhell.backend.community.service.SharedLinkService;
import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SharedLinkInternalController 集成测试。
 *
 * 覆盖 3 个端点 × 鉴权分支：
 * - 缺 header → 403
 * - 错 header → 403
 * - 正确 header → 200 / 409 / 404 等业务码
 *
 * "未配置 key → 503" 分支没单测，因为 @TestPropertySource 的值无法在方法级动态擦掉；
 * 该分支的正确性靠 controller 代码里对 isBlank 的显式 guard + 手工 smoke 覆盖。
 *
 * enrichment worker 需要 mock 掉，不让它在 H2 里真 fire async OG/分类（那会依赖
 * 外部 HTTP + DeepSeek API，测试里跑不通）。
 */
@TestPropertySource(properties = "internal.api-key=test-secret")
class SharedLinkInternalControllerIntegrationTests extends AbstractWebIntegrationTest {

    private static final String KEY_HEADER = "X-Internal-Key";
    private static final String KEY = "test-secret";

    /**
     * Mock 掉富化 worker：enrich 是 @Async 方法，正式环境会调外部服务。
     * 测试里只验 controller → service → DB 入库那一段，富化后的 status 流转靠 worker
     * 单测 {@link com.involutionhell.backend.community.service.SharedLinkEnrichmentWorkerTests} 覆盖。
     */
    @MockitoBean
    private com.involutionhell.backend.community.service.SharedLinkEnrichmentWorker worker;

    /**
     * OG / Classification 也 mock 掉，防止 Spring 上下文启动时去 new 真实 client。
     */
    @MockitoBean
    private com.involutionhell.backend.community.service.OgFetchService ogFetchService;

    @MockitoBean
    private com.involutionhell.backend.community.service.ClassificationService classificationService;

    @MockitoBean
    private com.involutionhell.backend.community.service.AlertWebhookClient alertWebhookClient;

    // ── 提交接口 ────────────────────────────────────────────────────────────
    @Test
    @DisplayName("POST /internal 缺 header → 403")
    void submit_missingKey_returns403() throws Exception {
        mockMvc.perform(post("/api/community/links/internal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("invalid internal key"));
    }

    @Test
    @DisplayName("POST /internal 错 header → 403")
    void submit_wrongKey_returns403() throws Exception {
        mockMvc.perform(post("/api/community/links/internal")
                        .header(KEY_HEADER, "WRONG_KEY")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /internal 正确 key → 200 + id 返回")
    void submit_correctKey_returns200() throws Exception {
        mockMvc.perform(post("/api/community/links/internal")
                        .header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "url": "https://arxiv.org/abs/2501.00001",
                                  "submitterLabel": "integration-test"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.host").value("arxiv.org"))
                .andExpect(jsonPath("$.data.recommendation")
                        .value("来自 Discord @integration-test"));
    }

    @Test
    @DisplayName("POST /internal 重复 url → 409")
    void submit_duplicateUrl_returns409() throws Exception {
        String body = """
                {
                  "url": "https://arxiv.org/abs/2501.00002",
                  "submitterLabel": "integration-test"
                }
                """;
        mockMvc.perform(post("/api/community/links/internal")
                        .header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/community/links/internal")
                        .header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("url already submitted"));
    }

    @Test
    @DisplayName("POST /internal 空 url → 400")
    void submit_missingUrl_returns400() throws Exception {
        mockMvc.perform(post("/api/community/links/internal")
                        .header(KEY_HEADER, KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"submitterLabel\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("url is required"));
    }

    // ── 只认本机直连（INV-013）──────────────────────────────────────────────
    // 经 Caddy / Vercel rewrite 转来的请求都带 X-Forwarded-For；key 正确也当接口不存在

    @Test
    @DisplayName("POST /internal 带 X-Forwarded-For → 404，key 对也不行，不落库")
    void submit_viaProxy_returns404() throws Exception {
        String url = "https://arxiv.org/abs/2610.10900";
        mockMvc.perform(post("/api/community/links/internal")
                        .header(KEY_HEADER, KEY)
                        .header("X-Forwarded-For", "203.0.113.7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shared_links WHERE url = ?", Integer.class, url))
                .isZero();
    }

    @Test
    @DisplayName("GET /internal/summary 与 /internal/{id} 带 X-Forwarded-For → 404")
    void readEndpoints_viaProxy_return404() throws Exception {
        mockMvc.perform(get("/api/community/links/internal/summary")
                        .header(KEY_HEADER, KEY).header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isNotFound());
        String url = "https://arxiv.org/abs/2610.10901";
        Integer id = com.jayway.jsonpath.JsonPath.read(submitAs(url, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.id");
        mockMvc.perform(get("/api/community/links/internal/" + id)
                        .header(KEY_HEADER, KEY).header("X-Forwarded-For", "203.0.113.7"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/community/links/internal/" + id).header(KEY_HEADER, KEY))
                .andExpect(status().isOk());
    }

    // ── 分享人归属（discordUserId）──────────────────────────────────────────
    // H2 库在整个测试进程内共享，用户名 / Discord id / URL 都取本类专用的值，避免撞别的测试

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SharedLinkService sharedLinkService;

    /** 别的测试会数用户总数，建的用户必须删掉（身份、链接随 FK 级联） */
    @AfterEach
    void cleanUpAttributionFixtures() {
        jdbc.update("DELETE FROM shared_links WHERE url LIKE 'https://arxiv.org/abs/2610.%'");
        jdbc.update("DELETE FROM user_accounts WHERE username LIKE 'share-attr-%'");
    }

    private long createUser(String username, boolean enabled) {
        jdbc.update("INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions) "
                + "VALUES (?, '!', ?, ?, 'user', '')", username, username, enabled);
        return jdbc.queryForObject("SELECT id FROM user_accounts WHERE username = ?", Long.class, username);
    }

    private void linkDiscord(long userId, String discordUserId) {
        jdbc.update("INSERT INTO user_identities (user_id, provider, provider_user_id) VALUES (?, 'discord', ?)",
                userId, discordUserId);
    }

    private long bridgeId() {
        return jdbc.queryForObject("SELECT id FROM user_accounts WHERE username = 'discord-bridge'", Long.class);
    }

    private String storedDiscordUserId(String url) {
        return jdbc.queryForObject("SELECT discord_user_id FROM shared_links WHERE url = ?", String.class, url);
    }

    private ResultActions submitAs(String url, String discordUserId) throws Exception {
        String idField = discordUserId == null ? "" : ",\"discordUserId\":\"" + discordUserId + "\"";
        return mockMvc.perform(post("/api/community/links/internal")
                .header(KEY_HEADER, KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"" + url + "\",\"submitterLabel\":\"someone\"" + idField + "}"));
    }

    @Test
    @DisplayName("已绑定 Discord 的分享人 → 链接记到本人名下，Discord id 落库")
    void submit_linkedDiscord_attributesToAccount() throws Exception {
        long userId = createUser("share-attr-linked", true);
        linkDiscord(userId, "900000000000000001");
        String url = "https://arxiv.org/abs/2610.10001";

        submitAs(url, "900000000000000001")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submitterId").value(userId))
                .andExpect(jsonPath("$.data.recommendation").value("来自 Discord @someone"));
        assertThat(storedDiscordUserId(url)).isEqualTo("900000000000000001");
    }

    @Test
    @DisplayName("Discord id 没绑定任何账号 → 挂 discord-bridge，但 id 照样落库")
    void submit_unlinkedDiscord_fallsBackToBridgeAndKeepsId() throws Exception {
        String url = "https://arxiv.org/abs/2610.10002";
        submitAs(url, "900000000000000002")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submitterId").value(bridgeId()));
        assertThat(storedDiscordUserId(url)).isEqualTo("900000000000000002");
    }

    @Test
    @DisplayName("绑定的账号已禁用 → 挂 discord-bridge")
    void submit_disabledAccount_fallsBackToBridge() throws Exception {
        long userId = createUser("share-attr-disabled", false);
        linkDiscord(userId, "900000000000000003");
        submitAs("https://arxiv.org/abs/2610.10003", "900000000000000003")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submitterId").value(bridgeId()));
    }

    @Test
    @DisplayName("不传 discordUserId（如 MCP 渠道）→ 挂 discord-bridge，id 为空")
    void submit_withoutDiscordId_staysOnBridge() throws Exception {
        String url = "https://arxiv.org/abs/2610.10004";
        submitAs(url, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.submitterId").value(bridgeId()));
        assertThat(storedDiscordUserId(url)).isNull();
    }

    @Test
    @DisplayName("discordUserId 不是纯数字 → 400，不落库")
    void submit_invalidDiscordId_returns400() throws Exception {
        String url = "https://arxiv.org/abs/2610.10005";
        submitAs(url, "12ab")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("invalid discordUserId"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shared_links WHERE url = ?", Integer.class, url))
                .isZero();
    }

    @Test
    @DisplayName("在 Discord 里分享的条数不占网页提交的每日配额")
    void bridgeShares_doNotCountTowardWebDailyLimit() throws Exception {
        long userId = createUser("share-attr-quota", true);
        linkDiscord(userId, "900000000000000006");
        for (int i = 0; i < 6; i++) {
            submitAs("https://arxiv.org/abs/2610.1010" + i, "900000000000000006")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.submitterId").value(userId));
        }
        assertThat(sharedLinkService.submit(userId, "https://arxiv.org/abs/2610.10200", null).submitterId())
                .isEqualTo(userId);
    }

    // ── summary 接口 ────────────────────────────────────────────────────────
    @Test
    @DisplayName("GET /internal/summary 缺 key → 403")
    void summary_missingKey_returns403() throws Exception {
        mockMvc.perform(get("/api/community/links/internal/summary"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /internal/summary 正确 key → 200 + 结构正常")
    void summary_correctKey_returns200() throws Exception {
        mockMvc.perform(get("/api/community/links/internal/summary")
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.pendingManual").isNumber())
                .andExpect(jsonPath("$.data.flagged").isNumber())
                .andExpect(jsonPath("$.data.approvedLast24h").isNumber())
                .andExpect(jsonPath("$.data.pendingSamples").isArray());
    }

    @Test
    @DisplayName("GET /internal/summary sampleLimit 上限 20")
    void summary_sampleLimit_capped() throws Exception {
        // sampleLimit=50 应该被截到 20。数据库里即便没有 50 条，也不能报错
        mockMvc.perform(get("/api/community/links/internal/summary")
                        .header(KEY_HEADER, KEY)
                        .param("sampleLimit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingSamples").isArray());
    }

    // ── GET by id 接口 ──────────────────────────────────────────────────────
    @Test
    @DisplayName("GET /internal/{id} 不存在 → 404")
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/community/links/internal/{id}", 999_999_999L)
                        .header(KEY_HEADER, KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("not found"));
    }

    @Test
    @DisplayName("GET /internal/{id} 缺 key → 403")
    void getById_missingKey_returns403() throws Exception {
        mockMvc.perform(get("/api/community/links/internal/{id}", 1L))
                .andExpect(status().isForbidden());
    }
}
