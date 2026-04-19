package com.involutionhell.backend.community.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Open Graph 元数据抓取服务（M2）。
 *
 * 职责：
 * 1. 用 JDK HttpClient 请求目标 URL，拿到 HTML
 * 2. 用 Jsoup 解析 og:title / og:description / og:image / og:site_name
 * 3. 失败时降级返回 OgFetchResult.failure(...)，不抛异常、不阻塞流程
 *
 * User-Agent 设成 Mozilla/5.0 兼容抓取机器人，避免被目标站点简单拦截。
 * 超时 10 秒，微信/知乎的 OG 标签在 HTML head 里，一般 2-5s 可拿到。
 *
 * 注意：本服务只抓 OG meta，不缓存、不转存正文（规避盗链）。
 */
@Service
public class OgFetchService {

    private static final Logger log = LoggerFactory.getLogger(OgFetchService.class);

    /** 抓取时声明的 User-Agent，模拟通用浏览器机器人。 */
    static final String USER_AGENT = "Mozilla/5.0 (compatible; InvolutionHellBot/1.0)";

    /** 单次请求超时（connect + read 合计），10 秒足够公众号/知乎。 */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    public OgFetchService() {
        // 自建 HttpClient，避免占用 openai 模块的 Bean；超时策略独立管理
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL) // 跟随 301/302（公众号有跳转）
                .build();
    }

    /** 测试注入点：允许传入 stub HttpClient。 */
    OgFetchService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 抓取指定 URL 的 Open Graph 元数据。
     *
     * @param url 已规范化的目标 URL
     * @return 抓取结果；失败时 errorMessage 非 null，og 字段全 null
     */
    public OgFetchResult fetch(String url) {
        log.debug("og-fetch 开始: url={}", url);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String reason = "HTTP " + response.statusCode();
                log.warn("og-fetch 失败（HTTP 非 2xx）: url={} status={}", url, response.statusCode());
                return OgFetchResult.failure(reason);
            }

            return parseOg(response.body(), url);

        } catch (IOException | InterruptedException e) {
            // IOException 包含超时、DNS 解析失败、连接拒绝等
            // InterruptedException 只在线程被中断时出现（异步 worker 关闭时）
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("og-fetch 网络异常: url={} error={}", url, reason);
            return OgFetchResult.failure(reason);
        } catch (Exception e) {
            // 兜底：URL 格式异常、Jsoup 解析异常等
            String reason = "解析异常: " + e.getMessage();
            log.warn("og-fetch 解析异常: url={} error={}", url, reason);
            return OgFetchResult.failure(reason);
        }
    }

    /**
     * 用 Jsoup 解析 HTML，提取 Open Graph meta 标签。
     *
     * 优先取 og: 命名空间的标准标签；
     * og:image 找不到时退而求其次取 twitter:image（不少平台两者都填了）。
     */
    OgFetchResult parseOg(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);

        String title = metaContent(doc, "og:title");
        String description = metaContent(doc, "og:description");
        String cover = metaContent(doc, "og:image");
        String siteName = metaContent(doc, "og:site_name");

        // 封面降级：部分平台只填 twitter:image
        if (cover == null) {
            cover = metaContent(doc, "twitter:image");
        }

        // 标题降级：og:title 没有时取 <title> 标签
        if (title == null) {
            Element titleEl = doc.selectFirst("title");
            if (titleEl != null) {
                title = titleEl.text();
            }
        }

        log.debug("og-fetch 解析完成: title={} siteName={}", title, siteName);
        return new OgFetchResult(title, description, cover, siteName, null);
    }

    /**
     * 提取 <meta property="..." content="..."> 或 <meta name="..." content="..."> 的 content 值。
     * 返回 null 表示该标签不存在。
     */
    private String metaContent(Document doc, String key) {
        // 标准 OG：<meta property="og:title" content="..." />
        Element el = doc.selectFirst("meta[property=" + key + "]");
        if (el == null) {
            // Twitter Card 用 name 属性：<meta name="twitter:image" content="..." />
            el = doc.selectFirst("meta[name=" + key + "]");
        }
        if (el == null) return null;
        String content = el.attr("content");
        return content.isBlank() ? null : content.trim();
    }
}
