package com.involutionhell.backend.community.site;

/**
 * 单站适配器：抓 OG meta 之前对 URL 做规范化，把"明知抓不到 OG"的 URL 重写到等价的可抓页面。
 *
 * 经典场景：
 * - arxiv.org/pdf/2603.15381 → arxiv.org/abs/2603.15381（pdf 是二进制，abs 才有 OG meta）
 * - scholar.google.com/scholar_url?url=&lt;real-link&gt; → 直接还原 real-link（scholar 是 click tracker）
 *
 * 设计：纯函数风格的 normalize 接口 —— 输入 URL 返回 URL，链式调用所有 adapter 直到没人改写。
 * 顺序无关：多个 adapter 之间不应有依赖。如果 normalize 后 URL 变了，重跑一轮（最多 3 跳，防环）。
 */
public interface SiteAdapter {

    /**
     * 尝试规范化 URL。如果不归本 adapter 处理，原样返回；归则返回新 URL。
     * 抛异常视为 adapter 内部 bug，调用方应记 warn 并跳过本 adapter，不要影响 fetch 流程。
     */
    String normalize(String url);
}
