package com.involutionhell.backend.analytics.controller;

import com.involutionhell.backend.analytics.dto.TopDocDto;
import com.involutionhell.backend.analytics.service.AnalyticsService;
import com.involutionhell.backend.analytics.service.Ga4UnavailableException;
import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import com.google.analytics.data.v1beta.BetaAnalyticsDataClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AnalyticsController 的 Web 集成测试。
 *
 * 继承 {@link AbstractWebIntegrationTest} 以复用仓库统一的数据源/JustAuth 覆盖，
 * 避免本测试自维护一份 @SpringBootTest 属性集，减少测试环境漂移。
 */
class AnalyticsControllerIntegrationTests extends AbstractWebIntegrationTest {

    // mock GA4 客户端 Bean，避免加载真实 gRPC 连接
    @MockitoBean
    private BetaAnalyticsDataClient betaAnalyticsDataClient;

    // 直接 mock service 层，精确控制返回值 / 验证参数
    @MockitoBean
    private AnalyticsService analyticsService;

    @Test
    void topDocsReturnsListWithDefaultParams() throws Exception {
        when(analyticsService.getTopDocs("30d", 20)).thenReturn(List.of(
                new TopDocDto("/docs/getting-started", "Getting Started", 1500),
                new TopDocDto("/docs/install", "Installation", 800)
        ));

        mockMvc.perform(get("/analytics/top-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].path").value("/docs/getting-started"))
                .andExpect(jsonPath("$.data[0].title").value("Getting Started"))
                .andExpect(jsonPath("$.data[0].views").value(1500))
                .andExpect(jsonPath("$.data[1].path").value("/docs/install"));
    }

    @Test
    void topDocsAcceptsWindowAndLimitParams() throws Exception {
        when(analyticsService.getTopDocs("7d", 5)).thenReturn(List.of(
                new TopDocDto("/docs/intro", "Intro", 200)
        ));

        mockMvc.perform(get("/analytics/top-docs?window=7d&limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].path").value("/docs/intro"));
    }

    @Test
    void topDocsUsesDefaultWindowForInvalidWindow() throws Exception {
        when(analyticsService.getTopDocs("30d", 20)).thenReturn(List.of());

        mockMvc.perform(get("/analytics/top-docs?window=invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        // 强断言：controller 必须把非法 window 回退到 30d 传给 service，
        // 否则即使忘了回退逻辑测试也会通过（service mock 对未命中 stub 返回 null）
        verify(analyticsService).getTopDocs("30d", 20);
    }

    @Test
    void topDocsCapsLimitAt100() throws Exception {
        when(analyticsService.getTopDocs("30d", 100)).thenReturn(List.of());

        mockMvc.perform(get("/analytics/top-docs?limit=999"))
                .andExpect(status().isOk());

        // 强断言：limit=999 必须被夹到 100 再进入 service
        verify(analyticsService).getTopDocs("30d", 100);
    }

    @Test
    void topDocsReturns503WhenGa4Unavailable() throws Exception {
        when(analyticsService.getTopDocs(anyString(), anyInt()))
                .thenThrow(new Ga4UnavailableException("GA4 连接失败", new RuntimeException("timeout")));

        mockMvc.perform(get("/analytics/top-docs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("数据分析服务暂时不可用，请稍后重试"));
    }
}
