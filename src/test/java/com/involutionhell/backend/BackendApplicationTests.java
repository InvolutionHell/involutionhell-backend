package com.involutionhell.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class BackendApplicationTests {

    /**
     * 验证 Spring Boot 测试上下文可以正常启动。
     */
    @Test
    void contextLoads() {
    }
}
