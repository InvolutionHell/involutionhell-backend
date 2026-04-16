package com.involutionhell.backend.docs.dto;

/**
 * 文档页底部"最近修改"那栏用的单条 commit。
 * 字段结构与前端 `app/types/docs-history.ts#HistoryItem` 对齐，
 * 以便前端直接拿来渲染不用做任何字段映射。
 *
 * @param sha         commit SHA
 * @param authorName  commit author 显示名
 * @param authorLogin 作者 GitHub login（当 commit email 没关联 GitHub 账号时回退到 name）
 * @param avatarUrl   作者 GitHub 头像 URL，空字符串代表"无头像，前端走占位资源"
 * @param date        ISO 时间
 * @param message     commit message 的第一行
 * @param htmlUrl     GitHub 上该 commit 的永久链接
 */
public record DocHistoryItemDto(
        String sha,
        String authorName,
        String authorLogin,
        String avatarUrl,
        String date,
        String message,
        String htmlUrl
) {}
