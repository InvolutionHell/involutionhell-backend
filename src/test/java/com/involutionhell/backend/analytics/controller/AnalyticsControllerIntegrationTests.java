package com.involutionhell.backend.analytics.controller;

import com.involutionhell.backend.analytics.service.AnalyticsService;
import com.involutionhell.backend.analytics.service.Ga4UnavailableException;
import com.involutionhell.backend.analytics.dto.TopDocDto;
import com.involutionhell.backend.analytics.config.Ga4ClientConfig;
import com.involutionhell.backend.analytics.service.Ga4ReportService;
import com.google.analytics.data.v1beta.BetaAnalyticsDataClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analytics_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql",
        "justauth.type.github.redirect-uri=https://example.com/api/auth/callback/github",
        "justauth.type.github.client-id=test-client-id",
        "justauth.type.github.client-secret=test-client-secret"
})
@AutoConfigureMockMvc
class AnalyticsControllerIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    // mock GA4 client 以避免真实 IO，同时 mock service 直接控制业务逻辑
    @MockitoBean
    private BetaAnalyticsDataClient betaAnalyticsDataClient;

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
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void topDocsCapsLimitAt100() throws Exception {
        when(analyticsService.getTopDocs("30d", 100)).thenReturn(List.of());

        mockMvc.perform(get("/analytics/top-docs?limit=999"))
                .andExpect(status().isOk());
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
