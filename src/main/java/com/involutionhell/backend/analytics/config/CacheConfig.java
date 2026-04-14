package com.involutionhell.backend.analytics.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * 开启 Spring Cache（使用 Caffeine 作为本地内存缓存）。
 * 独立成一个 @Configuration 类而不是写在主 Application 上，是为了让 @WebMvcTest 这类切片测试
 * 在不加载本类时自动跳过缓存，避免 @Cacheable 干扰 mock 行为。
 */
@Configuration
@EnableCaching
public class CacheConfig {}
