package com.involutionhell.backend.github;

/**
 * 个人主页上展示的 GitHub 公开仓库。
 *
 * @param name         仓库名（不含 owner）
 * @param fullName     owner/repo
 * @param description  仓库描述
 * @param htmlUrl      github.com 上的链接
 * @param language     主语言（可能 null）
 * @param stars        star 数
 * @param forks        fork 数
 * @param updatedAt    最近 push 时间（ISO）
 * @param fork         是否是 fork 的仓库（前端可以降低权重或过滤）
 */
public record GithubRepoDto(
        String name,
        String fullName,
        String description,
        String htmlUrl,
        String language,
        int stars,
        int forks,
        String updatedAt,
        boolean fork
) {}
