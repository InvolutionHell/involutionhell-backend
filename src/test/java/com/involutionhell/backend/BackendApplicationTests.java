package com.involutionhell.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// 显式覆盖数据源配置，防止 SPRING_DATASOURCE_URL 环境变量（指向生产 PostgreSQL）
// 优先于 application-test.properties，导致上下文加载失败。
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:backend;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql",
        "justauth.type.github.redirect-uri=https://example.com/api/auth/callback/github",
        "justauth.type.github.client-id=test-client-id",
        "justauth.type.github.client-secret=test-client-secret"
})
@ActiveProfiles("test")
class BackendApplicationTests {

    /**
     * 验证 Spring Boot 测试上下文可以正常启动。
     */
    @Test
    void contextLoads() {
    }
}
