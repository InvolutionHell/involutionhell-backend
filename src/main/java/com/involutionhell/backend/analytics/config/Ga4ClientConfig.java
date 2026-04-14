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

@Configuration
public class Ga4ClientConfig {

    private static final Logger log = LoggerFactory.getLogger(Ga4ClientConfig.class);

    @Bean
    public BetaAnalyticsDataClient betaAnalyticsDataClient(Ga4Properties ga4Properties) throws IOException {
        String credPath = ga4Properties.getCredentialsPath();
        log.info("初始化 GA4 客户端，凭证路径: {}", credPath);

        GoogleCredentials credentials = GoogleCredentials
                .fromStream(new FileInputStream(credPath))
                .createScoped("https://www.googleapis.com/auth/analytics.readonly");

        BetaAnalyticsDataSettings settings = BetaAnalyticsDataSettings.newBuilder()
                .setCredentialsProvider(() -> credentials)
                .build();

        return BetaAnalyticsDataClient.create(settings);
    }
}
