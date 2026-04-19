package com.involutionhell.backend.community.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * UrlNormalizer 与根域严格匹配的核心安全测试。
 *
 * 重点：
 * - `weixin.qq.com.evil.com` 这类钓鱼 URL 拿到的 host 应该是 evil.com 根域之下，
 *   **不能** 被白名单认为是 mp.weixin.qq.com
 * - http/https 以外的协议必须拒绝
 * - fragment 必须被剥离；query 必须保留（公众号用 query 定位文章）
 */
class UrlNormalizerTests {

    @Test
    void normalize_weixinHost_isExtractedLowercase() {
        var n = UrlNormalizer.normalize("https://mp.weixin.qq.com/s/abc123?foo=1");
        assertThat(n.host()).isEqualTo("mp.weixin.qq.com");
        assertThat(n.canonicalUrl()).isEqualTo("https://mp.weixin.qq.com/s/abc123?foo=1");
    }

    @Test
    void normalize_phishingDomain_hostIsNotWeixin() {
        // weixin.qq.com.evil.com 的真正 host 是 weixin.qq.com.evil.com，根域是 evil.com
        var n = UrlNormalizer.normalize("https://mp.weixin.qq.com.evil.com/s/abc");
        assertThat(n.host()).isEqualTo("mp.weixin.qq.com.evil.com");
        // 关键断言：白名单精确匹配一定 false
        assertThat(DomainWhitelist.contains(n.host())).isFalse();
    }

    @Test
    void normalize_realWeixin_whitelistMatches() {
        var n = UrlNormalizer.normalize("https://mp.weixin.qq.com/s/real-article");
        assertThat(DomainWhitelist.contains(n.host())).isTrue();
    }

    @Test
    void normalize_uppercaseHostIsLowered() {
        var n = UrlNormalizer.normalize("https://MP.Weixin.QQ.COM/s/abc");
        assertThat(n.host()).isEqualTo("mp.weixin.qq.com");
    }

    @Test
    void normalize_stripsFragment_preservesQuery() {
        var n = UrlNormalizer.normalize("https://zhuanlan.zhihu.com/p/12345?source=share#comment-1");
        assertThat(n.canonicalUrl()).isEqualTo("https://zhuanlan.zhihu.com/p/12345?source=share");
    }

    @Test
    void normalize_rejectsNonHttpScheme() {
        assertThatThrownBy(() -> UrlNormalizer.normalize("javascript:alert(1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlNormalizer.normalize("ftp://example.com/a"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlNormalizer.normalize("file:///etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalize_rejectsEmpty() {
        assertThatThrownBy(() -> UrlNormalizer.normalize(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlNormalizer.normalize("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UrlNormalizer.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sha256Hex_stableAndHex() {
        String h1 = UrlNormalizer.sha256Hex("https://mp.weixin.qq.com/s/abc");
        String h2 = UrlNormalizer.sha256Hex("https://mp.weixin.qq.com/s/abc");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64);
        assertThat(h1).matches("[0-9a-f]+");
    }
}
