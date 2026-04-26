package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.site.UrlNormalizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OgFetchService 单元测试。
 *
 * 使用手写 stub HttpClient（与 openai 模块测试风格一致），
 * 覆盖微信公众号 / 知乎 / 小红书三个平台的 OG 标签解析场景。
 */
class OgFetchServiceTests {

    /** 测试用空 normalizer：原样返回 URL，不做任何 site adapter 改写。 */
    private static final UrlNormalizer NOOP_NORMALIZER = new UrlNormalizer(List.of());


    // ── 微信公众号典型 HTML ──────────────────────────────────────────────
    private static final String WEIXIN_HTML = """
            <html>
            <head>
              <meta property="og:title" content="微信好文章标题" />
              <meta property="og:description" content="这是公众号文章摘要" />
              <meta property="og:image" content="https://mmbiz.qpic.cn/cover.jpg" />
              <meta property="og:site_name" content="某某公众号" />
            </head>
            <body></body>
            </html>
            """;

    // ── 知乎专栏典型 HTML ────────────────────────────────────────────────
    private static final String ZHIHU_HTML = """
            <html>
            <head>
              <meta property="og:title" content="如何学习编程？" />
              <meta property="og:description" content="知乎专栏文章摘要" />
              <meta property="og:image" content="https://pic1.zhimg.com/article-cover.jpg" />
              <meta property="og:site_name" content="知乎专栏" />
            </head>
            <body></body>
            </html>
            """;

    // ── 小红书 HTML（og:image 缺失，用 twitter:image 降级）────────────────
    private static final String XIAOHONGSHU_HTML = """
            <html>
            <head>
              <meta property="og:title" content="小红书分享笔记" />
              <meta property="og:description" content="这是笔记描述" />
              <meta name="twitter:image" content="https://sns-img.xhscdn.com/note.jpg" />
              <meta property="og:site_name" content="小红书" />
            </head>
            <body></body>
            </html>
            """;

    // 测试里的 host 一律用公网 IP 字面量（1.1.1.1 = Cloudflare DNS），
    // 因为 OgFetchService 在发请求前会用 PrivateAddressGuard 解析 host，
    // 用 mp.weixin.qq.com 这种真实域名会真的查 DNS，离线 CI 直接挂。
    // 站点平台维度的逻辑（公众号 / 知乎 / 小红书）由 OG meta 内容覆盖即可，
    // host 本身在这几条用例里不影响断言。

    // ── 服务器返回 403 Forbidden ─────────────────────────────────────────
    @Test
    void fetch_weixin_parsesOgTagsCorrectly() {
        OgFetchService service = new OgFetchService(stubHttpClient(200, WEIXIN_HTML), NOOP_NORMALIZER);
        OgFetchResult result = service.fetch("https://1.1.1.1/s/abc123");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.ogTitle()).isEqualTo("微信好文章标题");
        assertThat(result.ogDescription()).isEqualTo("这是公众号文章摘要");
        assertThat(result.ogCover()).isEqualTo("https://mmbiz.qpic.cn/cover.jpg");
        assertThat(result.ogSiteName()).isEqualTo("某某公众号");
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    void fetch_zhihu_parsesOgTagsCorrectly() {
        OgFetchService service = new OgFetchService(stubHttpClient(200, ZHIHU_HTML), NOOP_NORMALIZER);
        OgFetchResult result = service.fetch("https://1.1.1.1/p/12345");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.ogTitle()).isEqualTo("如何学习编程？");
        assertThat(result.ogDescription()).isEqualTo("知乎专栏文章摘要");
        assertThat(result.ogCover()).isEqualTo("https://pic1.zhimg.com/article-cover.jpg");
        assertThat(result.ogSiteName()).isEqualTo("知乎专栏");
    }

    @Test
    void fetch_xiaohongshu_fallsBackToTwitterImage() {
        // 小红书 og:image 缺失时应降级到 twitter:image
        OgFetchService service = new OgFetchService(stubHttpClient(200, XIAOHONGSHU_HTML), NOOP_NORMALIZER);
        OgFetchResult result = service.fetch("https://1.1.1.1/explore/abc");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.ogTitle()).isEqualTo("小红书分享笔记");
        // og:image 缺失，降级到 twitter:image
        assertThat(result.ogCover()).isEqualTo("https://sns-img.xhscdn.com/note.jpg");
    }

    @Test
    void fetch_httpError403_returnsFailureResult() {
        // 服务器返回 403 → 降级，不抛异常
        OgFetchService service = new OgFetchService(stubHttpClient(403, "Forbidden"), NOOP_NORMALIZER);
        OgFetchResult result = service.fetch("https://1.1.1.1/s/private");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).contains("403");
        assertThat(result.ogTitle()).isNull();
    }

    @Test
    void fetch_networkException_returnsFailureResult() {
        // 网络 IO 异常 → 降级，不抛异常
        OgFetchService service = new OgFetchService(new ThrowingHttpClient(
                new IOException("Connection refused")), NOOP_NORMALIZER);
        OgFetchResult result = service.fetch("https://1.1.1.1/s/timeout");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).containsIgnoringCase("Connection refused");
    }

    @Test
    void parseOg_missingAllOgTags_fallsBackToTitleTag() {
        // 没有任何 og: 标签时，标题从 <title> 降级获取
        OgFetchService service = new OgFetchService(stubHttpClient(200, ""), NOOP_NORMALIZER);
        String html = "<html><head><title>普通网页标题</title></head><body></body></html>";
        OgFetchResult result = service.parseOg(html, "https://example.com");

        // og:title 缺失，从 <title> 读
        assertThat(result.ogTitle()).isEqualTo("普通网页标题");
        // 其余 og 字段为 null（无对应标签）
        assertThat(result.ogDescription()).isNull();
        assertThat(result.isSuccess()).isTrue();
    }

    // ── 工具方法：构造返回固定状态码和 HTML 的 stub HttpClient ─────────────

    private static HttpClient stubHttpClient(int statusCode, String body) {
        return new StubHttpClient(statusCode, body);
    }

    // ── Stub HttpClient（与 openai 测试风格一致）────────────────────────────

    private static final class StubHttpClient extends HttpClient {

        private final int statusCode;
        private final String responseBody;

        StubHttpClient(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> responseBodyHandler) {
            return (HttpResponse<T>) new StubResponse<>(statusCode, responseBody);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        // ── 以下是 HttpClient 抽象方法的空实现 ──────────────────────────

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(10)); }
        @Override public Redirect followRedirects() { return Redirect.NORMAL; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
    }

    /** 始终抛 IOException 的 HttpClient，用于模拟网络故障。 */
    private static final class ThrowingHttpClient extends HttpClient {

        private final IOException exception;

        ThrowingHttpClient(IOException exception) {
            this.exception = exception;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            throw exception;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(10)); }
        @Override public Redirect followRedirects() { return Redirect.NORMAL; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
    }

    /**
     * Stub HTTP 响应。OgFetchService 现在用 BodyHandlers.ofInputStream()，
     * 所以 body() 要返回 ByteArrayInputStream；headers 也要塞 utf-8 charset
     * 走 resolveCharset 的 happy path。
     */
    private record StubResponse<T>(int statusCode, String rawBody) implements HttpResponse<T> {

        @Override
        @SuppressWarnings("unchecked")
        public T body() {
            return (T) new ByteArrayInputStream(rawBody.getBytes(StandardCharsets.UTF_8));
        }

        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(
                    Map.of("content-type", List.of("text/html; charset=utf-8")),
                    (k, v) -> true);
        }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://example.com"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
