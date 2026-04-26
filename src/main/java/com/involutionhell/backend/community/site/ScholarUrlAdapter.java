package com.involutionhell.backend.community.site;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * scholar.google.com/scholar_url?url=&lt;real-link&gt;&amp;... → 直接还原 real-link。
 *
 * 为什么：scholar_url 是 Google Scholar 的 click-tracking endpoint，
 * 任何外部调用（无 referer）都返回 HTTP 204 No Content + 空 body。
 * OG 抓取拿到空字符串，JSoup 解析啥也没有 → og 全 NULL 但 status=200 不报错。
 *
 * 真实文章链接就藏在 query 的 url= 参数里。提出来后会被链式重新过 adapter，
 * 比如 url= 是个 arxiv pdf，会再被 ArxivPdfAdapter 转成 abs 页。
 */
@Component
public class ScholarUrlAdapter implements SiteAdapter {

    @Override
    public String normalize(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return url;
            String hostLower = host.toLowerCase();
            // 只处理 scholar.google.com / .com.hk / .co.jp 等所有 Google Scholar 区域
            if (!hostLower.startsWith("scholar.google.")) {
                return url;
            }
            String path = uri.getPath();
            if (path == null || !path.equals("/scholar_url")) {
                return url;
            }
            String query = uri.getRawQuery();
            if (query == null) return url;
            // 手动解析 url= 参数 —— 不用 URLEncodedUtils 是为了避免引依赖
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String key = pair.substring(0, eq);
                if (!key.equals("url")) continue;
                String rawValue = pair.substring(eq + 1);
                String decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                // 防御：解码后必须是 http(s) 绝对 URL，否则原样返回
                if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                    return decoded;
                }
                return url;
            }
            return url;
        } catch (Exception e) {
            return url;
        }
    }
}
