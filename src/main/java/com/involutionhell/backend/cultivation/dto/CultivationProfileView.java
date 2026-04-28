package com.involutionhell.backend.cultivation.dto;

import java.time.Instant;

/**
 * 用户「修仙形态」的 LeetCode 个人档案视图。
 *
 * 设计取舍：
 * - 字段直接拍平在最外层，LeetCode 原始统计 + 修仙映射结果一次返回，
 *   前端不用再多打一发请求拼装。
 * - 不返回完整的境界表（realm 是后端固定数据），只返回当前境界
 *   currentRealm 和下一境界 nextRealm，避免前端代入 mapping 逻辑。
 * - progressToNext 用 0..1 的小数代替「还差几道题」，方便前端直接拿来
 *   渲染进度条；境界顶端 nextRealm == null 时约定取 1.0。
 * - lastSyncedAt 暴露同步时间，前端可据此提示「数据可能过期」并触发手动刷新。
 *
 * @param leetcodeUsername 用户绑定的 LeetCode 用户名
 * @param totalSolved      LeetCode 累计 AC 题目数
 * @param easySolved       其中简单题数量
 * @param mediumSolved     其中中等题数量
 * @param hardSolved       其中困难题数量
 * @param currentRealm     当前修仙境界（如 筑基 / 金丹 / 元婴）
 * @param nextRealm        下一境界；已到顶则为 null
 * @param progressToNext   当前境界到下一境界的进度，[0.0, 1.0]
 * @param lastSyncedAt     后端最近一次从 LeetCode 同步统计的时间
 */
public record CultivationProfileView(
        String leetcodeUsername,
        int totalSolved,
        int easySolved,
        int mediumSolved,
        int hardSolved,
        Realm currentRealm,
        Realm nextRealm,
        double progressToNext,
        Instant lastSyncedAt
) {

    /**
     * 修仙境界条目。
     * order 用于前端做境界排序展示，minSolved 表示「踏入此境所需的最少 AC 题数」。
     */
    public record Realm(String name, int order, int minSolved) {
    }
}
