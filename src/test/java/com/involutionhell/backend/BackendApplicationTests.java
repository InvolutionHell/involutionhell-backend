package com.involutionhell.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring Boot 上下文加载冒烟测试。
 *
 * <h3>为什么需要在这里覆盖数据源属性？</h3>
 * <p>原始代码使用裸 {@code @SpringBootTest}，未覆盖任何属性。
 * 当测试服务器设置了 {@code SPRING_DATASOURCE_URL} 环境变量（指向生产 Neon PostgreSQL）时，
 * 环境变量的优先级高于 {@code application-test.properties}，
 * Spring 会尝试用 PostgreSQL 驱动连接该 URL，而 H2 驱动拒绝 {@code jdbc:postgresql://} 格式，
 * 导致上下文启动失败，报错：
 * {@code Driver org.h2.Driver claims to not accept jdbcUrl, jdbc:postgresql://...}。</p>
 *
 * <p>{@code @SpringBootTest(properties)} 的优先级高于一切外部环境变量，
 * 可确保本测试始终在 H2 内存库上运行，与生产数据库完全隔离。</p>
 *
 * <p>同理，也覆盖了 JustAuth 的 redirect-uri：JustAuth 使用 Apache Commons
 * {@code UrlValidator} 在 {@link me.zhyd.oauth.request.AuthGithubRequest} 初始化时
 * 校验 redirect-uri，默认拒绝 localhost，故使用格式合法的占位 URL。</p>
 */
@SpringBootTest(properties = {
        // 覆盖 SPRING_DATASOURCE_URL 环境变量，强制使用 H2，详见类 Javadoc
        "spring.datasource.url=jdbc:h2:mem:backend;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql",
        // JustAuth UrlValidator 拒绝 localhost，使用合法占位 URL，不发起实际网络请求
        "justauth.type.github.redirect-uri=https://example.com/api/auth/callback/github",
        "justauth.type.github.client-id=test-client-id",
        "justauth.type.github.client-secret=test-client-secret"
})
@ActiveProfiles("test")
class BackendApplicationTests {

    /**
     * 验证 Spring Boot 测试上下文可以正常启动（所有 Bean 可注入、数据源可连接）。
     */
    @Test
    void contextLoads() {
    }
}
