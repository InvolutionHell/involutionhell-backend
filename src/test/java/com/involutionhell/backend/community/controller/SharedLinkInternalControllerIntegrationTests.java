package com.involutionhell.backend.community.controller;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
