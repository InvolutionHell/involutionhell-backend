package com.involutionhell.backend.docs.service;

import com.involutionhell.backend.docs.dto.LeaderboardEntryDto;
import com.involutionhell.backend.docs.model.DocContributor;
import com.involutionhell.backend.docs.repository.JdbcDocContributorRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 doc_contributors 全量记录按 github_id 聚合成排行榜条目。
 *
 * 这个 endpoint 服务于前端 Vercel 构建脚本 generate-leaderboard.mjs。
 * 之前脚本直接连 Postgres，导致每次 build 都要把 5432 暴露到公网；
 * 改走后端 API 后 DB 可以彻底收回内网。
 *
 * 缓存：Caffeine 10min（global TTL）。贡献者表更新频率低（commit 触发），
 * 10 分钟陈旧度对排行榜来说完全可以接受。
 */
@Service
public class LeaderboardService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcDocContributorRepository repository;

    public LeaderboardService(JdbcDocContributorRepository repository) {
        this.repository = repository;
    }

    /**
     * 返回所有 github_id 的聚合贡献数据。
     * 不做排序、不算 points、不查 GitHub login —— 那些是前端的事。
     */
    @Cacheable(value = "leaderboard", key = "'all'")
    public List<LeaderboardEntryDto> getLeaderboard() {
        List<DocContributor> all = repository.findAll();

        // 按 githubId 聚合：累加 contributions、收集 docId、按日分桶计数
        Map<Long, Aggregate> grouped = new LinkedHashMap<>();
        for (DocContributor c : all) {
            Aggregate agg = grouped.computeIfAbsent(c.githubId(), k -> new Aggregate());
            agg.contributions += c.contributions();
            agg.docIds.add(c.docId());
            if (c.lastContributedAt() != null) {
                String day = c.lastContributedAt().format(DAY_FMT);
                agg.dailyCounts.merge(day, c.contributions(), Integer::sum);
            }
        }

        List<LeaderboardEntryDto> result = new ArrayList<>(grouped.size());
        for (Map.Entry<Long, Aggregate> e : grouped.entrySet()) {
            result.add(new LeaderboardEntryDto(
                    e.getKey(),
                    e.getValue().contributions,
                    new ArrayList<>(e.getValue().docIds),
                    e.getValue().dailyCounts
            ));
        }
        return result;
    }

    /** 内部聚合状态。用 LinkedHashMap 保持 docId 加入顺序便于 debug。 */
    private static final class Aggregate {
        int contributions = 0;
        final List<String> docIds = new ArrayList<>();
        final Map<String, Integer> dailyCounts = new LinkedHashMap<>();
    }
}
