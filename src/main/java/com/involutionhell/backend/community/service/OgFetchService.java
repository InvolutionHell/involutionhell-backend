package com.involutionhell.backend.community.service;

import com.involutionhell.backend.common.security.PrivateAddressGuard;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

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
 * SSRF 防御：
 * - 每一跳（初次请求 + 每次 redirect）都用 {@link PrivateAddressGuard} 把 host
 *   解析成 IP 再逐 IP 过滤；命中内网 / loopback / link-local / CGNAT / multicast
 *   就拒绝（fail-closed，DNS 解析失败也拒绝）。
 * - 用 {@code Redirect.NEVER}，禁用 HttpClient 自己跟随；自己读 Location
 *   手动跳转（最多 3 跳），否则 JDK 默认会直接把我们扔到一个 169.254.169.254
 *   的 metadata endpoint 上。
 *
 * 注意：本服务只抓 OG meta，不缓存、不转存正文（规避盗链）。
 *
 * <h3>已知限制 — DNS rebinding 残留窗口</h3>
 * {@link PrivateAddressGuard#isBlockedHost(String)} 用
 * {@code InetAddress.getAllByName} 做一次 IP 判定，但 JDK {@code HttpClient}
 * 在建连接时会独立再解析一次 DNS；低 TTL（TTL=0）的攻击者域可以在两次解析
 * 之间把 A 记录从公网 IP 翻到 169.254.169.254。要彻底堵这一层需要换成
 * Apache HttpClient 5 或 OkHttp，注入自定义 {@code DnsResolver} 复用同一
 * 次解析结果，pin 到 guard 刚验过的 IP 上。属于后续工程化项，不在本 PR 范围。
 *
 * 补一点：即便换成 HC5 / OkHttp + 自定义 DnsResolver，也要把解析到的 IP
 * 直接交给 socket connect（而不是把 hostname 再传给连接器让它重解析）；
 * 同时 OS 层 nscd/systemd-resolved 缓存 + JVM {@code networkaddress.cache.ttl}
 * 都可能保留毫秒级残窗口。真正的彻底修复必须是“guard 拿到 IP → 直接用该
 * IP 建 socket + Host 头带原域名走 SNI”，靠 hostname 一路穿到底的实现都
 * 只是缩小窗口、不是关闭窗口。
 */
@Service
public class OgFetchService {

    private static final Logger log = LoggerFactory.getLogger(OgFetchService.class);

    /** 抓取时声明的 User-Agent，模拟通用浏览器机器人。 */
    static final String USER_AGENT = "Mozilla/5.0 (compatible; InvolutionHellBot/1.0)";

    /** 单次请求超时（connect + read 合计），10 秒足够公众号/知乎。 */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 最多允许 3 次 redirect；超过则按失败处理。 */
    static final int MAX_REDIRECTS = 3;

    /**
     * 响应体读取上限（2 MB）。OG meta 全在 {@code <head>} 里，2 MB 足够所有
     * 正常站点；超上限按“疑似恶意无限流 / chunked 无尽”处理，立刻中断读取。
     *
     * 之所以要在应用层兜底限流：JDK {@code BodyHandlers.ofString()} 本身
     * 没有 size 限制，配上 10 秒 timeout 仍然可能被攻击者的无限流撑爆堆。
     */
    static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final HttpClient httpClient;

    public OgFetchService() {
        // 自建 HttpClient，避免占用 openai 模块的 Bean；超时策略独立管理
        // followRedirects 改成 NEVER，redirect 由本类手动处理并在每一跳复查 host
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
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
            String currentUrl = url;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                // 每一跳都要重新校验 host —— 防止 302 把我们扔到内网
                URI uri;
                try {
                    uri = URI.create(currentUrl);
                } catch (IllegalArgumentException e) {
                    log.warn("og-fetch URL 非法: url={} error={}", currentUrl, e.getMessage());
                    return OgFetchResult.failure("invalid url: " + e.getMessage());
                }

                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    return OgFetchResult.failure("only http/https allowed, got: " + scheme);
                }

                String host = uri.getHost();
                if (host == null || host.isBlank()) {
                    return OgFetchResult.failure("url has no host");
                }

                if (PrivateAddressGuard.isBlockedHost(host)) {
                    log.warn("og-fetch 拒绝内网/回环 host: url={} host={}", currentUrl, host);
                    return OgFetchResult.failure("blocked internal host");
                }

                HttpRequest request = HttpRequest.newBuilder(uri)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .timeout(TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                // 3xx 手动跳转（最多 MAX_REDIRECTS 次），每一跳都会在下一轮循环里再次校验 host
                if (status >= 300 && status < 400) {
                    // 3xx 的 body 我们不需要，读完就走，防止连接泄漏
                    drainAndClose(response.body());
                    Optional<String> location = response.headers().firstValue("Location");
                    if (location.isEmpty() || location.get().isBlank()) {
                        log.warn("og-fetch 收到 3xx 但无 Location: url={} status={}", currentUrl, status);
                        return OgFetchResult.failure("HTTP " + status + " without Location");
                    }
                    if (hop == MAX_REDIRECTS) {
                        log.warn("og-fetch redirect 超过上限 {}: url={}", MAX_REDIRECTS, url);
                        return OgFetchResult.failure("too many redirects");
                    }
                    String next;
                    try {
                        next = resolveRedirect(uri, location.get());
                    } catch (IllegalArgumentException e) {
                        // 畸形 Location（带空格 / 非法字符 / 协议残缺）：不要让异常
                        // 漏到外层 catch(Exception) 里伪装成 "解析异常"，给一条结构化 msg
                        log.warn("og-fetch redirect Location 非法: url={} location={} error={}",
                                currentUrl, location.get(), e.getMessage());
                        return OgFetchResult.failure("redirect target invalid: " + e.getMessage());
                    }
                    log.debug("og-fetch redirect hop#{}: {} -> {}", hop + 1, currentUrl, next);
                    currentUrl = next;
                    continue;
                }

                if (status < 200 || status >= 300) {
                    drainAndClose(response.body());
                    String reason = "HTTP " + status;
                    log.warn("og-fetch 失败（HTTP 非 2xx）: url={} status={}", currentUrl, status);
                    return OgFetchResult.failure(reason);
                }

                // 2xx：流式读取 body，边读边计数；命中 MAX_BODY_BYTES 立刻中断
                Charset charset = resolveCharset(
                        response.headers().firstValue("Content-Type").orElse(null));
                BodyReadResult bodyResult = readBodyCapped(response.body(), charset);
                if (bodyResult.exceededLimit) {
                    log.warn("og-fetch 响应体超上限 {}B: url={}", MAX_BODY_BYTES, currentUrl);
                    return OgFetchResult.failure("response body exceeded max size");
                }

                return parseOg(bodyResult.body, currentUrl);
            }
            // 理论上走不到：for 循环里所有分支都 return
            return OgFetchResult.failure("unreachable");

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
     * 边读边计数的 body 读取：一次 read 最多 {@code MAX_BODY_BYTES} 字节；
     * 超上限立刻 close 流并返回 {@code exceededLimit=true}，不把后续字节收进 buffer。
     *
     * 选择 {@link ByteArrayOutputStream} + 手动循环而不是 {@code readAllBytes}
     * 是因为 readAllBytes 会在 close 前把整条流全吃进堆里，完全绕开我们的上限。
     */
    private static BodyReadResult readBodyCapped(InputStream in, Charset charset) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(
                    Math.min(64 * 1024, MAX_BODY_BYTES));
            byte[] chunk = new byte[8 * 1024];
            int total = 0;
            int n;
            while ((n = stream.read(chunk)) != -1) {
                if (total + n > MAX_BODY_BYTES) {
                    // 命中上限：只保留已读到上限的字节，丢弃超出部分，停止读取
                    int remaining = MAX_BODY_BYTES - total;
                    if (remaining > 0) {
                        buf.write(chunk, 0, remaining);
                    }
                    return new BodyReadResult(buf.toString(charset), true);
                }
                buf.write(chunk, 0, n);
                total += n;
            }
            return new BodyReadResult(buf.toString(charset), false);
        }
    }

    /**
     * 从 Content-Type 头提取 charset；缺失或无法识别时默认 UTF-8。
     * 公众号 / 知乎都是 UTF-8，少数站点（旧站、GBK 站）需要读 header 里的 charset 参数。
     */
    static Charset resolveCharset(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        // 一律在 lower 上定位并切片：原串上 "Charset=GBK" 这种大写写法时，
        // 如果在 lower 上找 idx、再回原串 substring，虽然 ASCII 长度守恒能碰巧
        // 对上，但以后哪怕有人把 lower 换成 toLowerCase(某 locale) 或加 trim，
        // 都会错位。靠 ASCII 长度巧合的代码不要留。
        String lower = contentType.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("charset=");
        if (idx < 0) {
            return StandardCharsets.UTF_8;
        }
        String raw = lower.substring(idx + "charset=".length()).trim();
        // 去掉后续 ;/空格 以及两侧引号
        int end = raw.length();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ';' || c == ' ' || c == '\t') { end = i; break; }
        }
        String name = raw.substring(0, end).replace("\"", "").replace("'", "").trim();
        if (name.isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        try {
            // Charset.forName 对 gbk / utf-8 / shift_jis 等都是大小写不敏感
            return Charset.forName(name);
        } catch (Exception e) {
            // 非法字符集名：按 UTF-8 兜底，不让整个抓取失败
            return StandardCharsets.UTF_8;
        }
    }

    /** 快速丢弃流并关闭，避免连接卡在 keep-alive 池里。最多丢 64 KB 就 break。 */
    private static void drainAndClose(InputStream in) {
        if (in == null) return;
        try (InputStream stream = in) {
            byte[] sink = new byte[8 * 1024];
            int drained = 0;
            int n;
            // 必须捕获 read 的实际返回值 n；原来直接 += sink.length 在流尾 / 短读场景
            // 下会高估已丢弃字节数，代码在骗读者。功能方向无害（只会更早 break），
            // 但计数撒谎，改掉。
            while (drained < 64 * 1024 && (n = stream.read(sink)) != -1) {
                drained += n;
            }
        } catch (IOException ignored) {
            // close / drain 失败忽略，主流程已决策返回 failure
        }
    }

    private record BodyReadResult(String body, boolean exceededLimit) {}

    /**
     * 把 Location 头（可能是绝对 URL，也可能是相对路径）解析成绝对 URL。
     * 畸形 Location 让 {@link URI#resolve(String)} 抛 {@link IllegalArgumentException}，
     * 由调用方转成结构化 failure —— 本方法刻意不吞。
     */
    private static String resolveRedirect(URI base, String location) {
        return base.resolve(location).toString();
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
