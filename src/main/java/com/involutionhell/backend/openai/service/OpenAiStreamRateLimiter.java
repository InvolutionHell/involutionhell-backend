package com.involutionhell.backend.openai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * /openai/responses/stream 的每用户限流（INV-006）。
 *
 * <p>背景（issue #297）：该端点烧的是付费 LLM 额度，此前只有 @SaCheckLogin
 * 没有限流——登录用户可绕过 Next.js 层的 Upstash 限流直接 curl 后端刷额度。
 * Caddy 是裸透传，所以限流必须落在 Java 层本身。
 *
 * <p>实现：Caffeine 固定窗口计数（每用户每分钟 N 次，写后 1 分钟过期）。
 * 进程内存级即可——后端单实例部署；将来横向扩容时换 Redis 计数即可，
 * 本类接口不变。窗口边界的突发（最多 2N/瞬间）对"防刷额度"场景无关紧要，
 * 不值得为此上滑动窗口。
 */
@Component
public class OpenAiStreamRateLimiter {

    private final int requestsPerMinute;
    private final Cache<Long, AtomicInteger> windows;

    // 有两个构造器时 Spring 需要显式指定注入入口，否则 context 起不来
    @Autowired
    public OpenAiStreamRateLimiter(
            @Value("${openai.stream.requests-per-minute:10}") int requestsPerMinute) {
        this(requestsPerMinute, Ticker.systemTicker());
    }

    /** 测试用：可注入假时钟推进窗口。 */
    OpenAiStreamRateLimiter(int requestsPerMinute, Ticker ticker) {
        this.requestsPerMinute = requestsPerMinute;
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .ticker(ticker)
                // 上限 = 防御性兜底：即使被恶意刷出海量 userId 也不至于撑爆内存
                .maximumSize(100_000)
                .build();
    }

    /**
     * 记一次调用；超限抛 429。
     *
     * @throws ResponseStatusException TOO_MANY_REQUESTS 当分钟窗口内已达上限
     */
    public void checkOrThrow(long userId) {
        AtomicInteger counter = windows.get(userId, id -> new AtomicInteger());
        if (counter.incrementAndGet() > requestsPerMinute) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "chat rate limit exceeded: " + requestsPerMinute + " requests/minute");
        }
    }
}
