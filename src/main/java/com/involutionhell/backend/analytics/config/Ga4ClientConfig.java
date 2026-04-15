package com.involutionhell.backend.analytics.config;

import com.google.analytics.data.v1beta.BetaAnalyticsDataClient;
import com.google.analytics.data.v1beta.BetaAnalyticsDataSettings;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 构造 BetaAnalyticsDataClient 单例 Bean 并注入 Service Account 凭证。
 * 客户端基于 gRPC，线程安全，在应用启动时创建一次即可；作用域仅申请 analytics.readonly，不写任何数据。
 *
 * 容错策略：凭证文件不存在不阻塞容器启动，而是注册一个"调用即抛 Ga4UnavailableException"的 stub
 * client。这样 GA4 接口运行时返回 503，其他业务（auth / preferences / 埋点）仍可用。
 * 之前直接 throw IOException 让 Spring ApplicationContext 起不来 → 整个后端崩，影响面过大。
 */
@Configuration
public class Ga4ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(Ga4ClientConfig.class);

    /**
     * 从本地 JSON 文件加载 Google Service Account 密钥构造 GA4 Data API 客户端。
     * 文件不存在或读取失败时记录 warning 并抛 IOException 让 Spring 跳过 bean 注册前的提前 check —
     * 实际改为返回一个调用失败的 stub。
     */
    @Bean
    public BetaAnalyticsDataClient betaAnalyticsDataClient(Ga4Properties ga4Properties) {
        String credPath = ga4Properties.getCredentialsPath();

        if (credPath == null || credPath.isBlank() || !Files.exists(Path.of(credPath))) {
            log.warn("GA4 凭证文件不存在或未配置 ({}), GA4 接口将返回 503，其余功能不受影响。",
                    credPath);
            return brokenClient(credPath);
        }

        log.info("初始化 GA4 客户端，凭证路径: {}", credPath);
        try (FileInputStream credentialsStream = new FileInputStream(credPath)) {
            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(credentialsStream)
                    .createScoped("https://www.googleapis.com/auth/analytics.readonly");

            BetaAnalyticsDataSettings settings = BetaAnalyticsDataSettings.newBuilder()
                    .setCredentialsProvider(() -> credentials)
                    .build();

            return BetaAnalyticsDataClient.create(settings);
        } catch (IOException e) {
            log.warn("GA4 客户端初始化失败 ({}): {}, 降级到 stub", credPath, e.getMessage());
            return brokenClient(credPath);
        }
    }

    /**
     * 构造一个"调用即失败"的 BetaAnalyticsDataClient，让 Ga4ReportService.fetchTopPaths
     * 走异常分支抛 Ga4UnavailableException → GlobalExceptionHandler 转 503。
     *
     * 用 BetaAnalyticsDataClient.create(settings) 配一个无效 endpoint 即可；
     * 实际方法调用会在网络层 / auth 层失败。
     */
    private BetaAnalyticsDataClient brokenClient(String credPath) {
        try {
            // 用 NoCredentialsProvider 让 client 能成功构造（不在 init 时调 lambda），
            // 实际 RPC 调用时会因为没凭证 + 无效 endpoint 立刻失败 →
            // Ga4ReportService 捕获 → Ga4UnavailableException → 503。
            BetaAnalyticsDataSettings settings = BetaAnalyticsDataSettings.newBuilder()
                    .setEndpoint("invalid.localhost:0")
                    .setCredentialsProvider(NoCredentialsProvider.create())
                    .build();
            log.warn("注册 GA4 stub client（凭证缺失：{}），所有 GA4 RPC 调用将返回 503", credPath);
            return BetaAnalyticsDataClient.create(settings);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to construct stub GA4 client; both real and stub init failed", e);
        }
    }
}
