package com.involutionhell.backend.analytics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GA4 连接配置，从 application.properties 的 ga4.* 前缀读取。
 * 两个字段都有默认值兜底（见 application.properties），正式环境由 .env 注入覆盖：
 * - propertyId：GA4 Property 数字 ID（不是 Measurement ID），在 GA4 控制台 Admin → Property Settings 获取
 * - credentialsPath：Google Cloud Service Account JSON 密钥的路径，相对工作目录
 */
@Component
@ConfigurationProperties(prefix = "ga4")
public class Ga4Properties {

    /** GA4 Property ID（纯数字，例如 504863779） */
    private String propertyId;
    /** Service Account JSON 凭证路径（切勿提交到 git，已加入 .gitignore） */
    private String credentialsPath;

    public String getPropertyId() { return propertyId; }
    public void setPropertyId(String propertyId) { this.propertyId = propertyId; }

    public String getCredentialsPath() { return credentialsPath; }
    public void setCredentialsPath(String credentialsPath) { this.credentialsPath = credentialsPath; }
}
