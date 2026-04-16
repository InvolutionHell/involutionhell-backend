package com.involutionhell.backend.docs.service;

import com.involutionhell.backend.docs.dto.DocHistoryItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 GitHub API 拉某个文档文件的最近 commit 历史。
 *
 * 替代前端原来的 /api/docs/history（Next API Route 打 Vercel Fluid CPU），
 * 挪到 Java 走 Caffeine 缓存：
 * - 同一 path 10 分钟内只打一次 GitHub API（10min 对 docs 更新来说足够低 staleness）
 * - 失败返回空列表（记日志但不向外抛），让前端页面正常渲染
 * - 需要 GITHUB_TOKEN 环境变量，否则匿名调用 60/hour 限流很快用完
 */
@Service
public class DocHistoryService {

    private static final Logger log = LoggerFactory.getLogger(DocHistoryService.class);
    private static final String REPO = "InvolutionHell/involutionhell";
    private static final int MAX_COMMITS = 5;

    private final RestClient github;
    private final ObjectMapper mapper;
    private final String token;

    public DocHistoryService(
            @Value("${GITHUB_TOKEN:}") String token,
            ObjectMapper mapper
    ) {
        this.token = token;
        this.mapper = mapper;
        this.github = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader("User-Agent", "involutionhell-backend")
                .build();
    }

    /**
     * 按仓库相对路径拉最近 5 条 commit。
     * path 需由调用方规范化好（已保证落在 app/docs/ 下）。
     *
     * @param path 仓库根相对路径，例如 "app/docs/ai/rl/index.mdx"
     * @return 最多 5 条 commit，按时间降序；调用失败返回空列表
     */
    @Cacheable(value = "docHistory", key = "#path", unless = "#result.isEmpty()")
    public List<DocHistoryItemDto> getHistory(String path) {
        if (path == null || path.isBlank()) return List.of();

        if (token == null || token.isBlank()) {
            log.warn("[DocHistoryService] GITHUB_TOKEN 未配置，GitHub API 匿名限流 60/hour，可能很快触发 403");
        }

        try {
            String body = github.get()
                    .uri(uri -> uri
                            .path("/repos/{owner}/{repo}/commits")
                            .queryParam("path", path)
                            .queryParam("per_page", MAX_COMMITS)
                            .build(REPO.split("/")[0], REPO.split("/")[1])
                    )
                    .headers(h -> {
                        if (token != null && !token.isBlank()) {
                            h.setBearerAuth(token);
                        }
                    })
                    .retrieve()
                    .body(String.class);
            return parseCommits(body);
        } catch (HttpClientErrorException e) {
            log.warn("[DocHistoryService] GitHub API 返回 {}: {}, path={}",
                    e.getStatusCode(), e.getStatusText(), path);
            return List.of();
        } catch (RestClientException e) {
            log.warn("[DocHistoryService] GitHub API 网络异常: {}, path={}", e.getMessage(), path);
            return List.of();
        }
    }

    /**
     * 把 GitHub commits 列表响应转成我们的 DTO。
     * author 为 null 时（commit email 没关联 GitHub 账号）avatarUrl 回空串让前端走占位。
     */
    private List<DocHistoryItemDto> parseCommits(String body) {
        List<DocHistoryItemDto> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        try {
            JsonNode arr = mapper.readTree(body);
            if (!arr.isArray()) return out;
            for (JsonNode c : arr) {
                String sha = text(c, "sha");
                JsonNode commit = c.get("commit");
                JsonNode author = c.get("author");
                JsonNode commitAuthor = commit != null ? commit.get("author") : null;

                String authorName = commitAuthor != null ? text(commitAuthor, "name") : "";
                String date = commitAuthor != null ? text(commitAuthor, "date") : "";
                String rawMessage = commit != null ? text(commit, "message") : "";
                String message = rawMessage.isEmpty() ? "" : rawMessage.split("\\r?\\n", 2)[0];

                String login = (author != null && !author.isNull()) ? text(author, "login") : authorName;
                String avatarUrl = (author != null && !author.isNull()) ? text(author, "avatar_url") : "";
                String htmlUrl = text(c, "html_url");

                out.add(new DocHistoryItemDto(sha, authorName, login, avatarUrl, date, message, htmlUrl));
            }
        } catch (Exception e) {
            log.warn("[DocHistoryService] 解析 GitHub commits 响应失败: {}", e.getMessage());
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }
}
