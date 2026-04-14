package com.involutionhell.backend.analytics.config;

import com.google.analytics.data.v1beta.BetaAnalyticsDataClient;
import com.google.analytics.data.v1beta.BetaAnalyticsDataSettings;
import com.google.auth.oauth2.GoogleCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * 构造 BetaAnalyticsDataClient 单例 Bean 并注入 Service Account 凭证。
 * 客户端基于 gRPC，线程安全，在应用启动时创建一次即可；作用域仅申请 analytics.readonly，不写任何数据。
 */
@Configuration
public class Ga4ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(Ga4ClientConfig.class);

    /**
     * 从本地 JSON 文件加载 Google Service Account 密钥构造 GA4 Data API 客户端。
     * 启动失败（如凭证路径不存在或无访问权限）会让容器启动失败，及时暴露配置问题。
     */
    @Bean
    public BetaAnalyticsDataClient betaAnalyticsDataClient(Ga4Properties ga4Properties) throws IOException {
        String credPath = ga4Properties.getCredentialsPath();
        log.info("初始化 GA4 客户端，凭证路径: {}", credPath);

        // 只申请只读权限（最小权限原则）；try-with-resources 显式关闭文件流，
        // GoogleCredentials.fromStream 并不保证替调用方关闭 InputStream
        GoogleCredentials credentials;
        try (FileInputStream credentialsStream = new FileInputStream(credPath)) {
            credentials = GoogleCredentials
                    .fromStream(credentialsStream)
                    .createScoped("https://www.googleapis.com/auth/analytics.readonly");
        }

        BetaAnalyticsDataSettings settings = BetaAnalyticsDataSettings.newBuilder()
                .setCredentialsProvider(() -> credentials)
                .build();

        return BetaAnalyticsDataClient.create(settings);
    }
}
