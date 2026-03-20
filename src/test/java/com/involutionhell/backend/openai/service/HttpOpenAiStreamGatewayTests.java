package com.involutionhell.backend.openai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.involutionhell.backend.openai.config.OpenAiProperties;
import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpOpenAiStreamGatewayTests {

    private final RecordingHttpClient httpClient = new RecordingHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validateConfigurationRejectsMissingEnvironmentValues() {
        HttpOpenAiStreamGateway gateway = new HttpOpenAiStreamGateway(
                httpClient,
                objectMapper,
                new OpenAiProperties("", "", "")
        );

        assertThatThrownBy(() -> gateway.validateConfiguration(new OpenAiStreamRequest("你好", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OPENAI_API_URL 未配置");
    }

    @Test
    void openStreamBuildsStreamingRequestFromEnvironmentConfiguration() throws Exception {
        HttpOpenAiStreamGateway gateway = new HttpOpenAiStreamGateway(
                httpClient,
                objectMapper,
                new OpenAiProperties("https://api.openai.com/v1/responses", "test-key", "gpt-4.1")
        );
        ByteArrayInputStream body = new ByteArrayInputStream("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        httpClient.response = new StubHttpResponse(200, body);

        InputStream result = gateway.openStream(new OpenAiStreamRequest("你好", "请简洁回答", null));
        HttpRequest capturedRequest = httpClient.lastRequest;
        assertThat(result).isSameAs(body);
        assertThat(capturedRequest.uri().toString()).isEqualTo("https://api.openai.com/v1/responses");
        assertThat(capturedRequest.headers().firstValue("Authorization")).hasValue("Bearer test-key");
        assertThat(capturedRequest.headers().firstValue("Accept")).hasValue("text/event-stream");
        assertThat(readRequestBody(capturedRequest))
                .contains("\"stream\":true")
                .contains("\"model\":\"gpt-4.1\"")
                .contains("\"instructions\":\"请简洁回答\"")
                .contains("\"type\":\"input_text\"")
                .contains("\"text\":\"你好\"");
    }

    @Test
    void openStreamAllowsPerRequestModelOverride() throws Exception {
        HttpOpenAiStreamGateway gateway = new HttpOpenAiStreamGateway(
                httpClient,
                objectMapper,
                new OpenAiProperties("https://api.openai.com/v1/responses", "test-key", "gpt-4.1")
        );
        httpClient.response = new StubHttpResponse(
                200,
                new ByteArrayInputStream("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8))
        );

        gateway.openStream(new OpenAiStreamRequest("你好", null, "gpt-5"));

        assertThat(readRequestBody(httpClient.lastRequest)).contains("\"model\":\"gpt-5\"");
    }

    @Test
    void openStreamRaisesReadableErrorWhenOpenAiReturnsFailure() throws Exception {
        HttpOpenAiStreamGateway gateway = new HttpOpenAiStreamGateway(
                httpClient,
                objectMapper,
                new OpenAiProperties("https://api.openai.com/v1/responses", "test-key", "gpt-4.1")
        );
        httpClient.response = new StubHttpResponse(401, new ByteArrayInputStream("""
                {"error":{"message":"Invalid API key"}}
                """.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> gateway.openStream(new OpenAiStreamRequest("你好", null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("Invalid API key");
    }

    /**
     * 订阅并提取 HttpRequest 内部的 BodyPublisher 文本，便于断言请求载荷。
     */
    private String readRequestBody(HttpRequest request) throws InterruptedException {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        CountDownLatch latch = new CountDownLatch(1);

        publisher.subscribe(new Flow.Subscriber<>() {

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                outputStream.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                latch.countDown();
            }

            @Override
            public void onComplete() {
                latch.countDown();
            }
        });

        if (!latch.await(1, TimeUnit.SECONDS)) {
            throw new IllegalStateException("读取请求体超时");
        }
        return outputStream.toString(StandardCharsets.UTF_8);
    }

    private static final class RecordingHttpClient extends HttpClient {

        private HttpRequest lastRequest;
        private HttpResponse<InputStream> response;

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(10));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }

        @Override
        public SSLParameters sslParameters() {
            return new SSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            lastRequest = request;
            @SuppressWarnings("unchecked")
            HttpResponse<T> typedResponse = (HttpResponse<T>) response;
            return typedResponse;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            throw new UnsupportedOperationException();
        }
    }

    private record StubHttpResponse(int statusCode, InputStream body) implements HttpResponse<InputStream> {

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://api.openai.com/v1/responses");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
