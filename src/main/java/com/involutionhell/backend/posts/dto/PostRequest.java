package com.involutionhell.backend.posts.dto;

import java.util.List;

/**
 * 创建/更新文章的请求体。
 *
 * slug 可选：不传时由 title 自动生成（title → kebab-case → 去重后缀）。
 * 更新时传 slug 可覆盖已有 slug，但需调用方保证唯一性（service 层会校验）。
 *
 * contentMd 是原始 markdown，图片已经是 R2 公开 URL（前端编辑器上传完成后替换 blob）。
 */
public record PostRequest(
        String       title,
        String       description,
        List<String> tags,
        String       contentMd,
        String       coverUrl,
        String       slug        // 可选，传 null 由 service 自动生成
) {}
