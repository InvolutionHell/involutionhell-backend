package com.involutionhell.backend.community.service;

/**
 * DeepSeek 分类结果（M3）。
 *
 * category 已由 LinkCategory.normalize() 保证合法；
 * flags 对应 DeepSeek 返回的安全判定：
 * - nsfw：色情/暴力等不适宜内容
 * - ad：广告/营销软文
 * - flame：引战/情绪化内容
 *
 * 任一 flag 为 true → worker 将 status 推到 FLAGGED。
 */
public record ClassificationResult(
        String category,
        boolean nsfw,
        boolean ad,
        boolean flame
) {

    /** 是否命中任意安全 flag。 */
    public boolean anyFlagSet() {
        return nsfw || ad || flame;
    }

    /** 降级结果：分类为 other，flags 全 false。 */
    public static ClassificationResult fallback() {
        return new ClassificationResult("other", false, false, false);
    }
}
