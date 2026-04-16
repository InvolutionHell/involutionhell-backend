package com.involutionhell.backend.github;

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
 * 拉用户公开 repos 作为个人主页的"Projects"数据源。
 *
 * - 不需要用户授权扩展 scope：走匿名 /users/{login}/repos 就能访问公开仓库
 * - 带 GITHUB_TOKEN 只是为了 5000/hour 限流（匿名 60/hour）
 * - Caffeine 缓存 1h（repo 列表变化慢，用户一天改几次 push 够用）
 * - 返回按 stars 降序 + 最近更新降序的 top 8，去掉 fork 仓库（可选）
 */
@Service
public class GithubReposService {

    private static final Logger log = LoggerFactory.getLogger(GithubReposService.class);
    private static final int PAGE_SIZE = 30;

    private final RestClient github;
    private final ObjectMapper mapper;
    private final String token;

    public GithubReposService(
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
     * 按 GitHub 数字 id 取 repos。先 /user/{id} 换 login，再调 listByLogin。
     * 中间 login 也做 Caffeine 缓存避免重复解析。
     */
    @Cacheable(value = "githubRepos", key = "'byId:' + #githubId", unless = "#result.isEmpty()")
    public List<GithubRepoDto> listByGithubId(long githubId) {
        try {
            String body = github.get()
                    .uri("/user/{id}", githubId)
                    .headers(h -> {
                        if (token != null && !token.isBlank()) {
                            h.setBearerAuth(token);
                        }
                    })
                    .retrieve()
                    .body(String.class);
            JsonNode node = mapper.readTree(body);
            String login = text(node, "login");
            if (login.isEmpty()) return List.of();
            return listByLogin(login);
        } catch (HttpClientErrorException e) {
            log.warn("[GithubReposService] GitHub /user/{} 返回 {}: {}",
                    githubId, e.getStatusCode(), e.getStatusText());
            return List.of();
        } catch (Exception e) {
            log.warn("[GithubReposService] /user/{} 请求失败: {}", githubId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 按 GitHub login 取公开仓库。最多返回 8 条，按 stars 降序 + updated_at 降序，
     * 默认过滤 fork（fork 过来的通常不是"自己的项目"）。
     *
     * @param login GitHub login，例如 "longsizhuo"
     */
    @Cacheable(value = "githubRepos", key = "'byLogin:' + #login", unless = "#result.isEmpty()")
    public List<GithubRepoDto> listByLogin(String login) {
        if (login == null || login.isBlank()) return List.of();

        try {
            String body = github.get()
                    .uri(uri -> uri
                            .path("/users/{login}/repos")
                            .queryParam("sort", "updated")
                            .queryParam("direction", "desc")
                            .queryParam("per_page", PAGE_SIZE)
                            .queryParam("type", "owner")
                            .build(login)
                    )
                    .headers(h -> {
                        if (token != null && !token.isBlank()) {
                            h.setBearerAuth(token);
                        }
                    })
                    .retrieve()
                    .body(String.class);
            return parseAndPick(body);
        } catch (HttpClientErrorException e) {
            log.warn("[GithubReposService] GitHub API 返回 {}: {}, login={}",
                    e.getStatusCode(), e.getStatusText(), login);
            return List.of();
        } catch (RestClientException e) {
            log.warn("[GithubReposService] GitHub API 网络异常: {}, login={}", e.getMessage(), login);
            return List.of();
        }
    }

    /**
     * 解析 /users/{login}/repos 响应 → 去 fork → 按 stars 和 updated_at 排序 → top 8。
     */
    private List<GithubRepoDto> parseAndPick(String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            JsonNode arr = mapper.readTree(body);
            if (!arr.isArray()) return List.of();

            List<GithubRepoDto> all = new ArrayList<>();
            for (JsonNode r : arr) {
                boolean isFork = r.path("fork").asBoolean(false);
                if (isFork) continue; // fork 的仓库一般不算用户自己的项目
                all.add(new GithubRepoDto(
                        text(r, "name"),
                        text(r, "full_name"),
                        text(r, "description"),
                        text(r, "html_url"),
                        text(r, "language"),
                        r.path("stargazers_count").asInt(0),
                        r.path("forks_count").asInt(0),
                        text(r, "updated_at"),
                        false
                ));
            }
            // stars desc → updated_at desc
            all.sort((a, b) -> {
                int s = Integer.compare(b.stars(), a.stars());
                if (s != 0) return s;
                return b.updatedAt().compareTo(a.updatedAt());
            });
            return all.size() > 8 ? all.subList(0, 8) : all;
        } catch (Exception e) {
            log.warn("[GithubReposService] 解析 GitHub repos 响应失败: {}", e.getMessage());
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? "" : v.asText();
    }
}
