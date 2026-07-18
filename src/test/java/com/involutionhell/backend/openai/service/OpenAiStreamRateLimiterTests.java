package com.involutionhell.backend.openai.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * INV-006 回归测试：/openai/responses/stream 的每用户限流。
 *
 * <p>背景见 issue #297——该端点烧付费 LLM 额度，登录用户可绕过前端限流
 * 直 curl 后端。此处守住：窗口内超限必 429、不同用户独立、窗口过期后恢复。
 */
class OpenAiStreamRateLimiterTests {

    /** 可手动推进的假时钟。 */
    private static final class FakeTicker implements Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        void advanceSeconds(long seconds) {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
        }
    }

    @Test
    void underLimitPassesAndOverLimitGets429() {
        OpenAiStreamRateLimiter limiter = new OpenAiStreamRateLimiter(3, new FakeTicker());

        assertThatCode(() -> {
            limiter.checkOrThrow(1L);
            limiter.checkOrThrow(1L);
            limiter.checkOrThrow(1L);
        }).doesNotThrowAnyException();

        assertThatThrownBy(() -> limiter.checkOrThrow(1L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
    }

    @Test
    void usersAreIsolated() {
        OpenAiStreamRateLimiter limiter = new OpenAiStreamRateLimiter(1, new FakeTicker());

        limiter.checkOrThrow(1L);
        // 用户 1 已满，用户 2 不受影响
        assertThatCode(() -> limiter.checkOrThrow(2L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.checkOrThrow(1L))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void windowExpiryResetsTheCounter() {
        FakeTicker ticker = new FakeTicker();
        OpenAiStreamRateLimiter limiter = new OpenAiStreamRateLimiter(1, ticker);

        limiter.checkOrThrow(1L);
        assertThatThrownBy(() -> limiter.checkOrThrow(1L))
                .isInstanceOf(ResponseStatusException.class);

        // 窗口（1 分钟）过期后计数清零
        ticker.advanceSeconds(61);
        assertThatCode(() -> limiter.checkOrThrow(1L)).doesNotThrowAnyException();
    }
}
