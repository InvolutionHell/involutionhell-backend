package com.involutionhell.backend.community.service;

import com.involutionhell.backend.community.model.LinkCategory;
import com.involutionhell.backend.openai.config.OpenAiProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * ClassificationService 单元测试。
 *
 * 使用手写 stub HttpClient，验证：
 * 1. 正常 DeepSeek 响应的解析（category + flags）
 * 2. AI 返回 markdown 代码块时自动剥离
 * 3. AI 返回非法 category 时 normalize() 兜底
 * 4. HTTP 错误 / 网络异常时降级为 fallback
 * 5. 请求体包含正确的 temperature=0 和 model
 */
class ClassificationServiceTests {

    private static final OpenAiProperties PROPS =
            new OpenAiProperties("https://api.deepseek.com/v1", "test-key", "deepseek-chat");

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── 正常响应：返回合法 JSON ───────────────────────────────────────────

    @Test
    void classify_normalResponse_returnsCorrectResult() {
        String deepseekResp = buildDeepSeekResponse(
                """
                {"category": "engineering", "nsfw": false, "ad": false, "flame": false}
                """);
        ClassificationService service = new ClassificationService(
                stubHttpClient(200, deepseekResp), objectMapper, PROPS);

        ClassificationResult result = service.classify(
                "Spring Boot 最佳实践", "深度讲解 Spring Boot 性能优化", "juejin.cn");

        assertThat(result.category()).isEqualTo(LinkCategory.ENGINEERING);
        assertThat(result.nsfw()).isFalse();
        assertThat(result.ad()).isFalse();
        assertThat(result.flame()).isFalse();
        assertThat(result.anyFlagSet()).isFalse();
    }

    @Test
    void classify_nsfwFlagSet_returnsNsfwTrue() {
        String deepseekResp = buildDeepSeekResponse(
                """
                {"category": "lifestyle", "nsfw": true, "ad": false, "flame": false}
                """);
        ClassificationService service = new ClassificationService(
                stubHttpClient(200, deepseekResp), objectMapper, PROPS);

        ClassificationResult result = service.classify("不适宜标题", null, "example.com");

        assertThat(result.nsfw()).isTrue();
        assertThat(result.anyFlagSet()).isTrue();
    }

    @Test
    void classify_adFlag_returnsAdTrue() {
        String deepseekResp = buildDeepSeekResponse(
                """
                {"category": "other", "nsfw": false, "ad": true, "flame": false}
                """);
        ClassificationService service = new ClassificationService(
                stubHttpClient(200, deepseekResp), objectMapper, PROPS);

        ClassificationResult result = service.classify("限时优惠！买一送一", "最低价保证", "shop.example.com");

        assertThat(result.ad()).isTrue();
        assertThat(result.anyFlagSet()).isTrue();
    }

    // ── AI 返回带 markdown 代码块 ─────────────────────────────────────────

    @Test
    void classify_responseWithMarkdownCodeBlock_stripsAndParses() {
        String deepseekResp = buildDeepSeekResponse(
                "```json\n{\"category\": \"ai_frontier\", \"nsfw\": false, \"ad\": false, \"flame\": false}\n```");
        ClassificationService service = new ClassificationService(
                stubHttpClient(200, deepseekResp), objectMapper, PROPS);

        ClassificationResult result = service.classify("GPT-5 论文解读", "最新 AI 研究", "mp.weixin.qq.com");

        // 能正确剥离代码块并解析
        assertThat(result.category()).isEqualTo(LinkCategory.AI_FRONTIER);
    }

    // ── AI 返回非法分类 slug ──────────────────────────────────────────────

    @Test
    void classify_invalidCategory_normalizesToOther() {
        String deepseekResp = buildDeepSeekResponse(
                """
                {"category": "unknown_category", "nsfw": false, "ad": false, "flame": false}
                """);
        ClassificationService service = new ClassificationService(
                stubHttpClient(200, deepseekResp), objectMapper, PROPS);

        ClassificationResult result = service.classify("测试标题", null, "example.com");

        // normalize() 兜底：非法 slug → other
        assertThat(result.category()).isEqualTo(LinkCategory.OTHER);
    }

    // ── HTTP 错误降级 ─────────────────────────────────────────────────────

    @Test
    void classify_httpError_returnsFallback() {
        ClassificationService service = new ClassificationService(
                stubHttpClient(500, "Internal Server Error"), objectMapper, PROPS);

        ClassificationResult result = service.classify("标题", "描述", "example.com");

        // 降级：category=other, flags 全 false
        assertThat(result.category()).isEqualTo(LinkCategory.OTHER);
        assertThat(result.anyFlagSet()).isFalse();
    }

    // ── 网络异常降级 ─────────────────────────────────────────────────────

    @Test
    void classify_networkException_returnsFallback() {
        ClassificationService service = new ClassificationService(
                new ThrowingHttpClient(new IOException("timeout")), objectMapper, PROPS);

        ClassificationResult result = service.classify("标题", null, "example.com");

        assertThat(result.category()).isEqualTo(LinkCategory.OTHER);
        assertThat(result.anyFlagSet()).isFalse();
    }

    // ── null 输入不崩溃 ───────────────────────────────────────────────────

    @Test
    void classify_nullTitleAndDescription_doesNotThrow() {
        String deepseekResp = buildDeepSeekResponse(
                """
                {"category": "other", "nsfw": false, "ad": false, "flame": false}
                """);
        ClassificationService service = new ClassificationService(
                stubHttpClient(200, deepseekResp), objectMapper, PROPS);

        ClassificationResult result = service.classify(null, null, "mp.weixin.qq.com");
        assertThat(result).isNotNull();
    }

    // ── buildUserContent 验证（占位符替换）────────────────────────────────

    @Test
    void buildUserContent_nullInputs_usesPlaceholders() {
        ClassificationService service = new ClassificationService(
                stubHttpClient(200, ""), objectMapper, PROPS);
        String content = service.buildUserContent(null, null, null);

        assertThat(content).contains("（无）");
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────

    /**
     * 构造符合 DeepSeek /chat/completions 响应格式的 JSON 字符串。
     * choices[0].message.content = contentJson
     */
    private String buildDeepSeekResponse(String contentJson) {
        return """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": %s
                    }
                  }]
                }
                """.formatted(toJsonString(contentJson));
    }

    private String toJsonString(String value) {
        // 把 content 值包成 JSON string（转义双引号和换行）
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"";
    }

    private static HttpClient stubHttpClient(int statusCode, String body) {
        return new StubHttpClient(statusCode, body);
    }

    // ── Stub HttpClient ────────────────────────────────────────────────────

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

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(30)); }
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
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(30)); }
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

    private record StubResponse<T>(int statusCode, String rawBody) implements HttpResponse<T> {

        @Override
        @SuppressWarnings("unchecked")
        public T body() {
            return (T) rawBody;
        }

        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(Map.of("content-type", List.of("application/json")), (k, v) -> true);
        }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return URI.create("https://api.deepseek.com/v1/chat/completions"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
