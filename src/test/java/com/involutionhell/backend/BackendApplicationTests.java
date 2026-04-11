package com.involutionhell.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring Boot 上下文加载冒烟测试。
 *
 * 原来用的是裸 @SpringBootTest，没覆盖任何属性。
 * 测试服务器上有 SPRING_DATASOURCE_URL 环境变量指向生产 Neon PostgreSQL，
 * 环境变量优先级高于 application-test.properties，Spring 就会去连 PostgreSQL，
 * H2 驱动拒绝 jdbc:postgresql:// 格式，直接报：
 * Driver org.h2.Driver claims to not accept jdbcUrl, jdbc:postgresql://...
 *
 * @SpringBootTest(properties) 的优先级比环境变量还高，所以在这里覆盖就能保证始终跑 H2。
 *
 * JustAuth 那几个属性也是同理：JustAuth 用 Apache Commons UrlValidator 校验 redirect-uri，
 * 它不接受 localhost，不覆盖的话 AuthGithubRequest 初始化直接抛异常。
 */
@SpringBootTest(properties = {
        // 覆盖 SPRING_DATASOURCE_URL 环境变量，强制使用 H2
        "spring.datasource.url=jdbc:h2:mem:backend;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql",
        // JustAuth UrlValidator 不接受 localhost，用合法占位 URL 绕过
        "justauth.type.github.redirect-uri=https://example.com/api/auth/callback/github",
        "justauth.type.github.client-id=test-client-id",
        "justauth.type.github.client-secret=test-client-secret"
})
@ActiveProfiles("test")
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }
}
