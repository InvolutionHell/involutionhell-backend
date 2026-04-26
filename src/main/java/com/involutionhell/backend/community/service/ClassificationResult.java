package com.involutionhell.backend.community.service;

/**
 * DeepSeek 分类结果（M3）。
 *
 * category 已由 LinkCategory.normalize() 保证合法；
 * flags 对应 DeepSeek 返回的安全/质量判定：
 * - nsfw：色情/暴力等不适宜内容
 * - ad：纯商业推广软文（技术公告/版本更新等不算）
 * - flame：引战/情绪化内容
 * - illegal：疑似违反中国法律法规（反动/颠覆/分裂/邪教/赌博/毒品等）
 * - notResource：链接本身不是"可分享的内容资源"（表情包/贴纸/GIF/裸图片/
 *                登录墙/错误页/dev PR 通知页等），客户端 listener 拦不住的兜底
 *
 * 任一 flag 为 true → worker 将 status 推到 FLAGGED（进人工复核）。
 */
public record ClassificationResult(
        String category,
        boolean nsfw,
        boolean ad,
        boolean flame,
        boolean illegal,
        boolean notResource
) {

    /** 是否命中任意安全/质量 flag。 */
    public boolean anyFlagSet() {
        return nsfw || ad || flame || illegal || notResource;
    }

    /** 降级结果：分类为 other，flags 全 false（网络/解析等**非内容过滤**原因的失败用）。 */
    public static ClassificationResult fallback() {
        return new ClassificationResult("other", false, false, false, false, false);
    }

    /**
     * 上游 provider 的 content filter 拦截了请求（如智谱 GLM error code 1301），
     * 说明内容本身命中了 provider 自己的安全策略——这种情况是强信号，
     * 本系统将 illegal 置为 true 让其走 FLAGGED 进人工复核，而不是 fallback 静默放行。
     */
    public static ClassificationResult blockedByContentFilter() {
        return new ClassificationResult("other", false, false, false, true, false);
    }
}
