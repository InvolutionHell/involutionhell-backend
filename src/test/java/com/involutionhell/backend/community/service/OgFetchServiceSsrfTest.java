package com.involutionhell.backend.community.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OgFetchService 的 SSRF 防御测试。
 *
 * 覆盖：
 * - 私网 IP 字面值 host（127.0.0.1 / 10.0.0.1）直接被 PrivateAddressGuard 挡掉，
 *   HttpClient 不应被调用到
 * - 公开 host 收到 302 指向 169.254.169.254（AWS metadata）时，手动 redirect
 *   逻辑会复查新 host，再次命中 PrivateAddressGuard 被挡
 * - 公开 host + 2xx 能正常解析 OG
 */
class OgFetchServiceSsrfTest {

    @Test
    void fetch_privateIpLoopback_blockedBeforeHttpCall() {
        // 127.0.0.1 应在 PrivateAddressGuard 阶段就被挡住，HttpClient 不应被调用
        RecordingHttpClient client = new RecordingHttpClient();
        OgFetchService service = new OgFetchService(client);

        OgFetchResult result = service.fetch("http://127.0.0.1/");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("blocked internal host");
        assertThat(client.sentRequests).as("请求不应被发出").isEmpty();
    }

    @Test
    void fetch_privateIpRfc1918_blockedBeforeHttpCall() {
        // 10.0.0.1 → RFC1918 私网，必须挡住
        RecordingHttpClient client = new RecordingHttpClient();
        OgFetchService service = new OgFetchService(client);

        OgFetchResult result = service.fetch("http://10.0.0.1/");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("blocked internal host");
        assertThat(client.sentRequests).isEmpty();
    }

    @Test
    void fetch_redirectToLinkLocalMetadata_blockedOnSecondHop() {
        // 公开 host 第一跳 200 是异常情况；我们用 302 → 169.254.169.254（AWS/GCP
        // metadata endpoint，link-local）。第二跳应在 PrivateAddressGuard 阶段被挡。
        // 用 1.1.1.1（Cloudflare DNS）这种公网 IP 字面量做第一跳，避免依赖外部 DNS。
        ScriptedHttpClient client = new ScriptedHttpClient(List.of(
                ScriptedResponse.redirect(302, "http://169.254.169.254/latest/meta-data/")
        ));
        OgFetchService service = new OgFetchService(client);

        OgFetchResult result = service.fetch("https://1.1.1.1/og-test");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("blocked internal host");
        // 第一跳被发出（拿到 302），第二跳的 host 解析后被挡，所以只有一次 HTTP 调用
        assertThat(client.sentRequests).hasSize(1);
    }

    @Test
    void fetch_publicHost200_parsesOgMeta() {
        String html = """
                <html><head>
                <meta property="og:title" content="公共站点 OK" />
                <meta property="og:description" content="stub 描述" />
                <meta property="og:image" content="https://cdn.example.com/x.jpg" />
                <meta property="og:site_name" content="Example" />
                </head><body></body></html>
                """;
        ScriptedHttpClient client = new ScriptedHttpClient(List.of(
                ScriptedResponse.ok(html)
        ));
        OgFetchService service = new OgFetchService(client);

        // 公网 IP 字面量：guard 不走 DNS，测试可在离线 / 受限 CI 里跑
        OgFetchResult result = service.fetch("https://1.1.1.1/og-article");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.ogTitle()).isEqualTo("公共站点 OK");
        assertThat(result.ogDescription()).isEqualTo("stub 描述");
        assertThat(result.ogCover()).isEqualTo("https://cdn.example.com/x.jpg");
        assertThat(result.ogSiteName()).isEqualTo("Example");
        assertThat(client.sentRequests).hasSize(1);
    }

    @Test
    void fetch_redirectWithGarbageLocation_returnsStructuredFailure() {
        // 畸形 Location（带空格 + 非法字符）让 URI.resolve 抛 IllegalArgumentException；
        // 我们必须把它转成结构化 failure("redirect target invalid: ...")，而不是
        // 从外层 catch(Exception) 里漏出成通用 "解析异常"
        ScriptedHttpClient client = new ScriptedHttpClient(List.of(
                ScriptedResponse.redirect(302, "ht!tp://bad host /x y")
        ));
        OgFetchService service = new OgFetchService(client);

        OgFetchResult result = service.fetch("https://1.1.1.1/og");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).startsWith("redirect target invalid");
        assertThat(client.sentRequests).hasSize(1);
    }

    @Test
    void fetch_bodyExceedsMaxSize_returnsFailure() {
        // 恶意公开 host 返回 > 2 MB 的 body —— 服务端必须边读边截断，
        // 不能把无限流整个吃进堆
        int oversize = OgFetchService.MAX_BODY_BYTES + 16 * 1024;
        byte[] payload = new byte[oversize];
        // 填可见字符避免读到全 0 被解析成空文档
        for (int i = 0; i < oversize; i++) payload[i] = 'A';

        ScriptedHttpClient client = new ScriptedHttpClient(List.of(
                ScriptedResponse.okRaw(payload)
        ));
        OgFetchService service = new OgFetchService(client);

        OgFetchResult result = service.fetch("https://1.1.1.1/huge");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("response body exceeded max size");
        assertThat(client.sentRequests).hasSize(1);
    }

    // ── 工具：可记录请求的 HttpClient ────────────────────────────────────────

    /** 始终抛 AssertionError 的 HttpClient — 用于确认请求未被发出。 */
    private static final class RecordingHttpClient extends HttpClient {
        final List<HttpRequest> sentRequests = new ArrayList<>();

        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> responseBodyHandler) {
            sentRequests.add(request);
            throw new AssertionError("不该走到 HTTP 层: " + request.uri());
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
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
    }

    /** 按注入顺序逐个返回预设响应的 HttpClient。 */
    private static final class ScriptedHttpClient extends HttpClient {
        final List<HttpRequest> sentRequests = new ArrayList<>();
        private final Deque<ScriptedResponse> queue;

        ScriptedHttpClient(List<ScriptedResponse> responses) {
            this.queue = new ArrayDeque<>(responses);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request,
                                        HttpResponse.BodyHandler<T> responseBodyHandler)
                throws IOException {
            sentRequests.add(request);
            ScriptedResponse resp = queue.pollFirst();
            if (resp == null) {
                throw new AssertionError("ScriptedHttpClient 响应队列已空");
            }
            return (HttpResponse<T>) new StubResponse<>(resp.status, resp.bodyBytes, resp.location, request.uri());
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
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.empty(); }
    }

    private record ScriptedResponse(int status, byte[] bodyBytes, String location) {
        static ScriptedResponse ok(String html) {
            return new ScriptedResponse(200, html.getBytes(StandardCharsets.UTF_8), null);
        }
        static ScriptedResponse okRaw(byte[] body) {
            return new ScriptedResponse(200, body, null);
        }
        static ScriptedResponse redirect(int code, String location) {
            return new ScriptedResponse(code, new byte[0], location);
        }
    }

    private record StubResponse<T>(int statusCode, byte[] rawBody, String location, URI uri)
            implements HttpResponse<T> {

        @Override
        @SuppressWarnings("unchecked")
        public T body() {
            // 改流式读取后，body() 必须返回 InputStream
            return (T) new ByteArrayInputStream(rawBody);
        }

        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            Map<String, List<String>> map = (location == null)
                    ? Map.of("content-type", List.of("text/html; charset=utf-8"))
                    : Map.of("content-type", List.of("text/html"), "location", List.of(location));
            return HttpHeaders.of(map, (k, v) -> true);
        }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
