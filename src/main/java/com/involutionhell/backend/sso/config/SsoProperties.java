package com.involutionhell.backend.sso.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IH 通行证的 client 表（INV-010）：{@code sso.clients.<clientId>.secret / .redirect-uri}。
 * 接新站点只加配置，代码里不认任何具体的 client 名。
 */
@ConfigurationProperties(prefix = "sso")
public record SsoProperties(Map<String, Client> clients) {

    public SsoProperties {
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    /**
     * @param secret      换码接口认 client 用的共享密钥
     * @param redirectUri 唯一允许的回跳地址，签码时逐字比较
     */
    public record Client(String secret, String redirectUri) {

        /**
         * secret 和 redirect-uri 都配了才算启用，否则两个接口都拒绝（fail closed）。
         * redirect-uri 为空也算未启用：不然请求里传空串就能和登记值"逐字相等"。
         */
        public boolean enabled() {
            return secret != null && !secret.isBlank() && redirectUri != null && !redirectUri.isBlank();
        }
    }
}
