package com.involutionhell.backend.community.service;

import com.involutionhell.backend.common.security.PrivateAddressGuard;
import com.involutionhell.backend.community.site.UrlNormalizer;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * User-Agent：用纯浏览器伪装，**不带 Bot 字样**。
     *
     * 历史踩坑：原来用 "Mozilla/5.0 (compatible; InvolutionHellBot/1.0)"，
     * 微信公众号 (mp.weixin.qq.com) 见到 Bot 字样会返回精简版 17KB HTML，
     * 里面没有 og:title/og:description，连 <title> 都被剥掉。
     * 换成正常 Chrome UA 后能拿到完整 4MB+ 文章页（OG 在前几 KB 的 head 里）。
     *
     * 我们仍然遵守 robots.txt 是另一回事（由 robotstxt parser 处理，本服务只
     * 负责抓 OG meta），UA 伪装只为绕过那些靠 UA 字符串做"软反爬"的站点。
     */
    static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * <code>&lt;/head&gt;</code> 字节序列。流式抓取时一旦读到这个 marker 就立即停止
     * 后续读取——OG meta 全在 head 里，再多读 body 部分就是浪费带宽 + 撑爆内存。
     * 用 ASCII 字节比较避免编码转换开销（HTML 标签本身就是 ASCII）。
     */
    private static final byte[] HEAD_END_MARKER =
            "</head>".getBytes(StandardCharsets.US_ASCII);

    /**
     * 单次请求超时，10 秒足够公众号/知乎把 response head + OG meta 所在的
     * {@code <head>} 段发回来。
     *
     * <p>注意语义：改用 {@link HttpResponse.BodyHandlers#ofInputStream()} 之后，
     * 这里的 timeout 只覆盖 connect + TLS 握手 + 收到 response head（以及 JDK
     * 内部把响应对象返回给调用方之前的行为），并不限制随后从 InputStream
     * 逐块 read 的耗时。所以不是老版那种“connect + read 合计 10s 上限”。
     * 对无限流 / 慢发字节的防御是 {@link #MAX_BODY_BYTES} 尺寸上限 +
     * {@link #readBodyCapped} 的主动中断，而不是这里的 timeout。
     */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 最多允许 3 次 redirect；超过则按失败处理。 */
    static final int MAX_REDIRECTS = 3;

    /**
     * 响应体读取上限（16 MB）。OG meta 全在 {@code <head>} 里，理论上几 KB 够用，
     * 但部分站点（尤其微信公众号 mp.weixin.qq.com）会在 head 之前塞 megabyte 量级
     * 的 inline base64 logo + 内联 CSS + 大段编辑器初始化 JSON。
     *
     * 历史：原 8MB，线上 id=20 那条微信文章触发了 "response body exceeded max size"
     * —— head 还没读到 inline base64 资源就把上限吃满了。提到 16MB 覆盖目前已知
     * 所有"正常但臃肿"的站点，再大就走图片代理（长期方案）兜底。
     *
     * 真正的优化是 {@link #HEAD_END_MARKER} 早停 —— 流式扫描遇到 {@code </head>}
     * 立即停读，绝大多数站点只读几十 KB 就够。这个上限只是无限流防御兜底。
     *
     * 之所以要在应用层兜底限流：JDK {@code BodyHandlers.ofString()} 本身
     * 没有 size 限制，配上 10 秒 timeout 仍然可能被攻击者的无限流撑爆堆。
     */
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;

    private final HttpClient httpClient;
    private final UrlNormalizer urlNormalizer;

    /**
     * Spring 注入入口。@Autowired 显式标注是因为本类还存在 package-private 测试 ctor，
     * Spring 看到两个 ctor 会拒绝自动选（NoUniqueBeanDefinitionException 反向版）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public OgFetchService(UrlNormalizer urlNormalizer) {
        this.urlNormalizer = urlNormalizer;
        // 自建 HttpClient，避免占用 openai 模块的 Bean；超时策略独立管理
        // followRedirects 改成 NEVER，redirect 由本类手动处理并在每一跳复查 host
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 测试注入点：允许传入 stub HttpClient + normalizer。 */
    OgFetchService(HttpClient httpClient, UrlNormalizer urlNormalizer) {
        this.httpClient = httpClient;
        this.urlNormalizer = urlNormalizer;
    }

    /**
     * 抓取指定 URL 的 Open Graph 元数据。
     *
     * @param url 原始 URL（内部会先过 SiteAdapter 链做规范化）
     * @return 抓取结果；失败时 errorMessage 非 null，og 字段全 null
     */
    public OgFetchResult fetch(String url) {
        log.debug("og-fetch 开始: url={}", url);
        if (url == null || url.isBlank()) {
            return OgFetchResult.failure("url is null or blank");
        }
        // 先过 site adapter 链：把已知抓不到 OG 的 URL 重写到等价可抓页面
        // (arxiv pdf → abs, scholar_url → 真实链接, ...)
        // 容错：normalize 在异常或 url 为 null 时可能返回 null，回退到原 URL，避免后续 NPE。
        String normalized = urlNormalizer.normalize(url);
        if (normalized == null) {
            normalized = url;
        } else if (!normalized.equals(url)) {
            log.info("og-fetch URL 规范化: {} -> {}", url, normalized);
        }
        try {
            String currentUrl = normalized;
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

                // 区分 DNS_FAIL 和 BLOCKED：之前都返回 "blocked internal host"，
                // 用户敲错域名时排障误以为我们在审查他的链接
                PrivateAddressGuard.CheckResult check = PrivateAddressGuard.resolveAndCheck(host);
                switch (check) {
                    case DNS_FAIL -> {
                        log.warn("og-fetch DNS 解析失败: url={} host={}", currentUrl, host);
                        return OgFetchResult.failure("dns lookup failed: " + host);
                    }
                    case BLOCKED -> {
                        log.warn("og-fetch 拒绝内网/回环 host: url={} host={}", currentUrl, host);
                        return OgFetchResult.failure("blocked internal host");
                    }
                    case OK -> {
                        // continue
                    }
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

                // 2xx：先看 Content-Type，非 HTML（如 application/pdf、image/*）
                // 直接软失败 —— 把 PDF 二进制流塞给 JSoup 解析既浪费 CPU 又必然返回空 OG。
                // 调用方（SharedLinkEnrichmentWorker）会按这个 errorMessage 决定走 LLM 兜底。
                String contentType = response.headers().firstValue("Content-Type").orElse(null);
                if (!isHtmlContentType(contentType)) {
                    drainAndClose(response.body());
                    String typeForMsg = contentType == null ? "(missing)" : contentType.split(";", 2)[0].trim();
                    log.info("og-fetch 跳过非 HTML 响应: url={} content-type={}", currentUrl, typeForMsg);
                    return OgFetchResult.failure("non-html content-type: " + typeForMsg);
                }

                // 流式读取 body，边读边计数；命中 MAX_BODY_BYTES 或 </head> 立刻中断
                Charset charset = resolveCharset(contentType);
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
     * 优化：每个 chunk 写入 buffer 后扫一次 {@link #HEAD_END_MARKER}，命中
     * {@code </head>} 立即停读 —— OG meta 全在 head 里，没必要把整个 body 吞进堆。
     * 微信公众号文章典型情况下 head 在前 50KB 内，body 含 megabyte 级 base64
     * 图片，早停能省 99% 带宽 + 内存。
     *
     * 选择 {@link ByteArrayOutputStream} + 手动循环而不是 {@code readAllBytes}
     * 是因为 readAllBytes 会在 close 前把整条流全吃进堆里，完全绕开我们的上限。
     */
    private static BodyReadResult readBodyCapped(InputStream in, Charset charset) throws IOException {
        try (InputStream stream = in) {
            ExposedByteArrayOutputStream buf = new ExposedByteArrayOutputStream(
                    Math.min(64 * 1024, MAX_BODY_BYTES));
            byte[] chunk = new byte[8 * 1024];
            int total = 0;
            int n;
            while ((n = stream.read(chunk)) != -1) {
                if (total + n > MAX_BODY_BYTES) {
                    int remaining = MAX_BODY_BYTES - total;
                    if (remaining > 0) {
                        buf.write(chunk, 0, remaining);
                    }
                    return new BodyReadResult(buf.toString(charset), true);
                }
                buf.write(chunk, 0, n);
                total += n;
                // 早停：扫描 buffer 末尾找 </head>。回看 chunk 大小 + marker 长度
                // 即可，不用每次扫整个 buffer。命中后剩余 body 直接丢弃，不影响 OG 解析。
                if (containsHeadEndNearTail(buf, n + HEAD_END_MARKER.length)) {
                    return new BodyReadResult(buf.toString(charset), false);
                }
            }
            return new BodyReadResult(buf.toString(charset), false);
        }
    }

    /**
     * 在 buffer 末尾 {@code lookbackBytes} 范围内查找 {@code </head>}。
     * 直接读 ExposedByteArrayOutputStream 的内部数组，避免 toByteArray() 每个 chunk
     * 都复制整个已读内容（在 4MB+ 页面上是 O(n²) 级别的拷贝开销）。
     */
    private static boolean containsHeadEndNearTail(ExposedByteArrayOutputStream buf, int lookbackBytes) {
        int size = buf.size();
        if (size < HEAD_END_MARKER.length) return false;
        int from = Math.max(0, size - lookbackBytes);
        byte[] all = buf.internalBuffer();
        for (int i = from; i <= size - HEAD_END_MARKER.length; i++) {
            // 大小写不敏感比较：HTML 既允许 </head> 也允许 </HEAD>
            boolean match = true;
            for (int j = 0; j < HEAD_END_MARKER.length; j++) {
                byte a = all[i + j];
                byte b = HEAD_END_MARKER[j];
                // ASCII 大小写折叠
                if (a >= 'A' && a <= 'Z') a += 32;
                if (b >= 'A' && b <= 'Z') b += 32;
                if (a != b) { match = false; break; }
            }
            if (match) return true;
        }
        return false;
    }

    /**
     * 暴露 {@link ByteArrayOutputStream} 内部 buffer 的子类，用于 marker 扫描时零拷贝读取。
     * 注意 internalBuffer() 长度可能大于 size()，调用方必须用 {@link #size()} 限定有效范围。
     */
    private static final class ExposedByteArrayOutputStream extends ByteArrayOutputStream {
        ExposedByteArrayOutputStream(int initialCapacity) { super(initialCapacity); }
        byte[] internalBuffer() { return buf; }
    }

    /**
     * Content-Type 是否属于 HTML 系列（接受 text/html、application/xhtml+xml、application/xml 等）。
     * 缺失的 Content-Type 也按 HTML 处理（少数小站完全不发这个 header），让后续 JSoup 自己判定。
     */
    static boolean isHtmlContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true; // 缺失视为可能 HTML，让 JSoup 兜底
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        return lower.startsWith("text/html")
                || lower.startsWith("application/xhtml")
                || lower.startsWith("application/xml")
                || lower.startsWith("text/xml")
                || lower.startsWith("text/plain"); // 少数 CMS 误标 text/plain 但实际是 HTML
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
     * 微信公众号封面图正则。公众号文章 head 里**没有** {@code <meta property="og:image">}，
     * 封面 URL 埋在 {@code <script>} 里的 JS 变量：
     * <pre>
     *   var msg_cdn_url = "http://mmbiz.qpic.cn/sz_mmbiz_jpg/xxxxx/0?wx_fmt=jpeg";
     *   var cdn_url_1_1 = "http://mmbiz.qpic.cn/.../640";  // 备用
     *   var msg_cover_url = "...";                          // 极少数模板
     * </pre>
     * 三个变量按优先级依次匹配，第一个命中就返回。值常以 http:// 开头，由
     * {@link #upgradeMediaProtocol} 统一升级到 https。
     *
     * 安全：内容必须以 http(s):// 开头，杜绝 javascript: / data: 等被偷换的可能。
     */
    private static final Pattern WEIXIN_COVER_PATTERN = Pattern.compile(
            "var\\s+(?:msg_cdn_url|cdn_url_1_1|msg_cover_url)\\s*=\\s*[\"'](https?://[^\"'\\s]+)[\"']",
            Pattern.CASE_INSENSITIVE);

    /**
     * 用 Jsoup 解析 HTML，提取 Open Graph meta 标签。
     *
     * 封面查找顺序：
     *   1. {@code <meta property="og:image">}（标准 OG）
     *   2. {@code <meta name="twitter:image">}（部分平台只填 Twitter Card）
     *   3. WeChat fallback：扫 {@code var msg_cdn_url = "..."} 一类 JS 变量
     *      （公众号 head 里没有 og:image，封面图全埋在 inline script 里）
     *   4. 最后做 http -> https 升级，避免在 HTTPS 页面上被 mixed-content 拦截
     */
    OgFetchResult parseOg(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl);

        String title = metaContent(doc, "og:title");
        String description = metaContent(doc, "og:description");
        String cover = metaContent(doc, "og:image");
        String siteName = metaContent(doc, "og:site_name");

        // 封面降级 1：部分平台只填 twitter:image
        if (cover == null) {
            cover = metaContent(doc, "twitter:image");
        }

        // 封面降级 2：微信公众号专项 —— 公众号 head 没 og:image，得扫 JS 变量
        // 不限定 host：少数自建站点（如转载公众号文章的内容农场）也保留了这些
        // 变量名，多兜一手没坏处。正则强约束开头必须是 http(s):// 防 XSS。
        if (cover == null) {
            cover = findWeixinCover(html);
        }

        // 统一升级 http -> https：xhscdn / mmbiz / pic.zhimg 等主流图床都支持 https，
        // 留 http 会被浏览器 mixed-content policy 直接拦掉
        cover = upgradeMediaProtocol(cover);

        // 标题降级：og:title 没有时取 <title> 标签
        if (title == null) {
            Element titleEl = doc.selectFirst("title");
            if (titleEl != null) {
                title = titleEl.text();
            }
        }

        log.debug("og-fetch 解析完成: title={} siteName={} hasCover={}",
                title, siteName, cover != null);
        return new OgFetchResult(title, description, cover, siteName, null);
    }

    /**
     * 微信公众号封面提取：扫 inline script 里的 msg_cdn_url / cdn_url_1_1 等变量。
     * 找不到返回 null（让调用方进入下一级 fallback 或显示占位）。
     *
     * 注：理论上 head 早停应该把 inline script 也一起读到（公众号封面 JS 一般在
     * head 内）；万一 script 在 body 才出现，下次再补 body 扫描。
     */
    static String findWeixinCover(String html) {
        if (html == null || html.isEmpty()) return null;
        Matcher m = WEIXIN_COVER_PATTERN.matcher(html);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * 把媒体 URL 的 http:// 升级为 https://。
     * <p>
     * 触发场景：小红书 og:image 值是 {@code http://sns-webpic-qc.xhscdn.com/...}，
     * 微信 msg_cdn_url 是 {@code http://mmbiz.qpic.cn/...}。浏览器在 HTTPS 页面
     * 加载这些资源会被 mixed-content policy 拦截。三大主流图床（xhscdn / mmbiz /
     * pic.zhimg）都同时支持 https，盲升级安全。
     * <p>
     * 不动协议相对 URL（{@code //example.com/x.jpg}）和已是 https 的 URL。
     * 非 http/https 协议（如 data:、ftp:）原样返回，由上层的 sanitizeMediaUrl 处理。
     */
    static String upgradeMediaProtocol(String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.regionMatches(true, 0, "http://", 0, 7)) {
            return "https://" + url.substring(7);
        }
        return url;
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
