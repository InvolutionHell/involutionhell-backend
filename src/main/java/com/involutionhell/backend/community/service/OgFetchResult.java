package com.involutionhell.backend.community.service;

/**
 * OG 抓取结果。
 *
 * errorMessage 非 null 表示本次抓取失败（降级）：
 * - 链接仍保留，前端展示"封面/摘要未能抓取"弱提示
 * - 只有 og* 字段全为 null，errorMessage 记录失败原因
 *
 * 设计成 record，避免 null 误用：调用方用 isSuccess() 先判断。
 */
public record OgFetchResult(
        String ogTitle,
        String ogDescription,
        String ogCover,
        String ogSiteName,
        String errorMessage
) {

    /** 抓取成功（不保证字段全部非空，平台可能不输出完整 OG 标签）。 */
    public boolean isSuccess() {
        return errorMessage == null;
    }

    /** 快捷构造：失败降级，仅携带错误原因。 */
    public static OgFetchResult failure(String reason) {
        return new OgFetchResult(null, null, null, null, reason);
    }
}
