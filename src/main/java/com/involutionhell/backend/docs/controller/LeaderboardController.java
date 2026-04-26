package com.involutionhell.backend.docs.controller;

import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.docs.dto.LeaderboardEntryDto;
import com.involutionhell.backend.docs.service.LeaderboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 排行榜公开只读接口。供前端 Vercel 构建脚本拉聚合数据，不需要登录。
 *
 * 路径前缀 /api/public/* 是新约定：所有完全公开、build-time 可调的接口都挂这里，
 * SaToken 白名单一行 /api/public/** 通配，避免每次新加公开接口都要回去改白名单。
 */
@RestController
@RequestMapping("/api/public")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    public LeaderboardController(LeaderboardService leaderboardService) {
        this.leaderboardService = leaderboardService;
    }

    @GetMapping("/leaderboard")
    public ApiResponse<List<LeaderboardEntryDto>> getLeaderboard() {
        return ApiResponse.ok(leaderboardService.getLeaderboard());
    }
}
