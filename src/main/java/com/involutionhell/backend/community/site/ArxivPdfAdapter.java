package com.involutionhell.backend.community.site;

import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * arxiv.org/pdf/&lt;id&gt;[.pdf] → arxiv.org/abs/&lt;id&gt;。
 *
 * 为什么：pdf 链接 Content-Type 是 application/pdf，不是 HTML，OG 抓取必败。
 * abs 页是同一篇论文的 HTML landing page，带完整 OG meta（title、authors、abstract）。
 *
 * 容错：
 * - 不强求 .pdf 后缀（arxiv 经常省略），只要路径以 /pdf/ 开头就重写
 * - 保留 arxiv id 末尾可能的版本号（v1 / v2），版本是 abstract 页支持的
 * - host 接受 arxiv.org / www.arxiv.org / export.arxiv.org
 */
@Component
public class ArxivPdfAdapter implements SiteAdapter {

    @Override
    public String normalize(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return url;
            String hostLower = host.toLowerCase();
            if (!hostLower.equals("arxiv.org")
                    && !hostLower.equals("www.arxiv.org")
                    && !hostLower.equals("export.arxiv.org")) {
                return url;
            }
            String path = uri.getPath();
            if (path == null || !path.startsWith("/pdf/")) {
                return url;
            }
            // /pdf/2603.15381        → /abs/2603.15381
            // /pdf/2603.15381v2      → /abs/2603.15381v2
            // /pdf/2603.15381.pdf    → /abs/2603.15381
            String id = path.substring("/pdf/".length());
            if (id.endsWith(".pdf")) {
                id = id.substring(0, id.length() - ".pdf".length());
            }
            // 规范化端只用 abs；query / fragment 一概丢弃（pdf 上的 query 对 abs 无意义）
            return new URI(uri.getScheme(), uri.getAuthority(), "/abs/" + id, null, null).toString();
        } catch (Exception e) {
            // 任何解析异常都 fail-soft 返回原 URL
            return url;
        }
    }
}
