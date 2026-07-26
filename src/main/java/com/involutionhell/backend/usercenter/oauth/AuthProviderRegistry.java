package com.involutionhell.backend.usercenter.oauth;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * provider 名 → {@link AuthProvider} 的唯一映射。Spring 注入所有 AuthProvider bean，
 * 按 key 建索引；新增 provider 不需要改这个类。
 *
 * 这是"provider 名"在整个后端的**单一真相源**：控制器和服务一律经此查找，
 * 不再各自维护 switch。
 */
@Component
public class AuthProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(AuthProviderRegistry.class);

    private final Map<String, AuthProvider> byKey;

    public AuthProviderRegistry(List<AuthProvider> providers) {
        this.byKey = providers.stream().collect(Collectors.toUnmodifiableMap(
                p -> p.key().toLowerCase(Locale.ROOT),
                Function.identity(),
                (a, b) -> {
                    // 同名两个实现 = 行为不确定，启动即失败好过线上随机命中一个
                    throw new IllegalStateException(
                            "重复的 AuthProvider key: " + a.key() + "（" + a.getClass() + " / " + b.getClass() + "）");
                }));
        log.info("[Auth] 已注册 {} 个登录 provider: {}", byKey.size(), byKey.keySet());
    }

    /** 找不到返回空（未知 provider），由调用方兜底到错误页而不是 500。 */
    public Optional<AuthProvider> find(String key) {
        return key == null ? Optional.empty()
                : Optional.ofNullable(byKey.get(key.toLowerCase(Locale.ROOT)));
    }

    /** 已注册的 provider 名，供前端展示"可绑定的登录方式"。 */
    public java.util.Set<String> keys() {
        return byKey.keySet();
    }
}
