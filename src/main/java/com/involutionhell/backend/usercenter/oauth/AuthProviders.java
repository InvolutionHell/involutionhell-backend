package com.involutionhell.backend.usercenter.oauth;

/** AuthProvider 实现共用的小工具。 */
final class AuthProviders {

    private AuthProviders() {
    }

    /**
     * client-id 与 secret 都要有：只配一半时提前挡在 oauth_provider（配置问题），
     * 而不是让 token 交换阶段以 oauth_failed 失败——后者会误导成"provider 侧拒绝"。
     */
    static boolean isConfigured(String clientId, String clientSecret) {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    static void requireConfigured(String provider, String clientId, String clientSecret) {
        if (!isConfigured(clientId, clientSecret)) {
            throw new IllegalArgumentException(provider + " OAuth 未配置（缺 client-id 或 secret）");
        }
    }
}
