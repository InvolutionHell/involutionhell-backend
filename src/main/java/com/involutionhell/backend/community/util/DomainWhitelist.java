package com.involutionhell.backend.community.util;

import java.util.Set;

/**
 * 域名白名单：命中者免人工审核（但仍过机器闸 AI 安全判定）。
 *
 * 策略：严格根域精确匹配，绝不用 endsWith / contains。
 * host 必须已由 UrlNormalizer 规范化（小写）。
 *
 * 增删白名单：改这里 → 评估一次 link_reports 历史 → 跑回归。
 */
public final class DomainWhitelist {

    private static final Set<String> HOSTS = Set.of(
            // 微信公众号
            "mp.weixin.qq.com",

            // 知乎
            "zhuanlan.zhihu.com",
            "www.zhihu.com",

            // 小红书（站内文章以 xhslink 短链为主，但原始长链也收）
            "www.xiaohongshu.com",
            "xiaohongshu.com",

            // 技术 / 科普向补充
            "juejin.cn",
            "www.36kr.com",
            "36kr.com",
            "sspai.com",

            // 学术 / 开源社区：AI/CS 方向的正式论文与代码（用户画像契合）
            // arxiv 是 pre-print 官方仓库，不会托管色情/赌博；个别争议论文仍由
            // AI 的 nsfw/ad/flame flag 兜底。github/huggingface 同理，整体信任度够高。
            "arxiv.org",
            "www.arxiv.org",
            "github.com",
            "huggingface.co"
    );

    public static boolean contains(String host) {
        if (host == null) return false;
        return HOSTS.contains(host);
    }

    private DomainWhitelist() {}
}
