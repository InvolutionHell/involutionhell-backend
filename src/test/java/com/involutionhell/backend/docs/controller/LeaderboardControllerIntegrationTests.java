package com.involutionhell.backend.docs.controller;

import com.involutionhell.backend.docs.dto.LeaderboardEntryDto;
import com.involutionhell.backend.docs.service.LeaderboardService;
import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/public/leaderboard 集成测试。
 *
 * 重点回归两件事：
 *   1. SaToken 白名单：匿名（无 satoken header）也能 200，否则 /api/public/** 通配
 *      被人误改写就会立刻挂前端 build 链。
 *   2. ApiResponse + LeaderboardEntryDto 字段结构：githubId/contributions/docIds/dailyCounts
 *      改名前端脚本就崩，加锁。
 */
class LeaderboardControllerIntegrationTests extends AbstractWebIntegrationTest {

    @MockitoBean
    private LeaderboardService leaderboardService;

    @Test
    void leaderboardReturnsAggregatedDataForAnonymous() throws Exception {
        when(leaderboardService.getLeaderboard()).thenReturn(List.of(
                new LeaderboardEntryDto(
                        114939201L,
                        237,
                        List.of("doc-a", "doc-b"),
                        Map.of("2026-04-25", 200, "2026-04-26", 37)
                ),
                new LeaderboardEntryDto(
                        99887766L,
                        12,
                        List.of("doc-c"),
                        Map.of("2026-04-20", 12)
                )
        ));

        // 不带 satoken header，验证白名单生效
        mockMvc.perform(get("/api/public/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].githubId").value(114939201L))
                .andExpect(jsonPath("$.data[0].contributions").value(237))
                .andExpect(jsonPath("$.data[0].docIds").isArray())
                .andExpect(jsonPath("$.data[0].docIds[0]").value("doc-a"))
                .andExpect(jsonPath("$.data[0].dailyCounts['2026-04-25']").value(200))
                .andExpect(jsonPath("$.data[1].githubId").value(99887766L))
                .andExpect(jsonPath("$.data[1].contributions").value(12));
    }

    @Test
    void leaderboardReturnsEmptyArrayWhenNoData() throws Exception {
        when(leaderboardService.getLeaderboard()).thenReturn(List.of());

        mockMvc.perform(get("/api/public/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
