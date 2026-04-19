package com.involutionhell.backend.community.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * URL 规范化与根域提取。
 *
 * 核心防御目标：阻止 `weixin.qq.com.evil.com/xxx` 这类钓鱼 URL 冒充白名单域名。
 * 做法：解析成 URI 取真正的 host，逐字段精确匹配，绝不用 contains / endsWith。
 *
 * 同时负责：
 * - trim / 去 fragment / 小写 host（URL 路径大小写敏感，保留）
 * - 生成 urlHash 用于去重
 */
public final class UrlNormalizer {

    public record Normalized(String canonicalUrl, String host) {}

    /**
     * 规范化 URL 并返回 (canonicalUrl, host)。
     * host 已转小写，canonicalUrl 去掉了 fragment。
     *
     * @throws IllegalArgumentException 协议不是 http/https、缺 host、URI 格式错误
     */
    public static Normalized normalize(String raw) {
        if (raw == null) throw new IllegalArgumentException("url is null");
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("url is empty");

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("url syntax invalid: " + e.getMessage(), e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("only http/https allowed, got: " + scheme);
        }

        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("url has no host");
        }
        host = host.toLowerCase(Locale.ROOT);

        // 剥 fragment（#锚点）；保留 query 因为公众号等平台用 query 识别文章
        String canonical = rebuildWithoutFragment(uri, host);

        return new Normalized(canonical, host);
    }

    /** sha256 hex，用于 url_hash 唯一索引去重。 */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // JDK 必有 SHA-256，理论上不会进这里
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String rebuildWithoutFragment(URI uri, String lowerHost) {
        StringBuilder sb = new StringBuilder();
        sb.append(uri.getScheme()).append("://");
        if (uri.getUserInfo() != null) sb.append(uri.getUserInfo()).append('@');
        sb.append(lowerHost);
        if (uri.getPort() != -1) sb.append(':').append(uri.getPort());
        if (uri.getRawPath() != null) sb.append(uri.getRawPath());
        if (uri.getRawQuery() != null) sb.append('?').append(uri.getRawQuery());
        return sb.toString();
    }

    private UrlNormalizer() {}
}
