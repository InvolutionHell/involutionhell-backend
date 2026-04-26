package com.involutionhell.backend.docs.dto;

import java.util.List;
import java.util.Map;

/**
 * 排行榜单条记录（聚合后的贡献者数据）。
 *
 * 故意不算 points / 不查 GitHub login / 不解析 doc title，
 * 只返回纯数据库聚合结果。原因：
 *   - 前端 build 脚本本身已经有 .source/index.ts → docId→title 映射，后端不要重复造
 *   - login 由前端从 git log noreply 邮箱反推（98% 命中），后端只在缺数据时打 GitHub API 就够，
 *     这一层兜底也已经在脚本里实现了，没必要挪到后端
 *   - points 公式（commit × 10）属于展示策略，留给前端调
 *
 * 这样后端 endpoint 是 stateless 纯读，缓存语义干净。
 */
public record LeaderboardEntryDto(
        Long githubId,
        int contributions,
        List<String> docIds,
        Map<String, Integer> dailyCounts
) {}
