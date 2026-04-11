package com.involutionhell.backend.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web 集成测试公共基类，提供 MockMvc 和预置登录辅助方法。
 *
 * 为什么要在 @SpringBootTest 里显式覆盖数据源属性？
 * Spring Boot 属性优先级：@SpringBootTest(properties) > 环境变量 > application-test.properties。
 * 测试服务器上存在 SPRING_DATASOURCE_URL 环境变量，指向生产 Neon PostgreSQL，
 * 它的优先级高于 application-test.properties 里的 H2 配置，
 * 导致测试启动时直接去连 PostgreSQL，H2 驱动拒绝 jdbc:postgresql:// 格式，上下文崩掉。
 * 把数据源写进 @SpringBootTest(properties) 就能盖过环境变量，保证测试始终跑 H2。
 *
 * 为什么还要覆盖 JustAuth redirect-uri？
 * JustAuth 用 Apache Commons UrlValidator 校验 redirect-uri，
 * 而 UrlValidator 默认不接受 localhost 域名。
 * application.properties 里默认是 http://localhost:3000/...，
 * 不覆盖的话 OAuthController 初始化 AuthGithubRequest 时直接抛 AuthException，
 * 所有 OAuth 相关测试都会 500。换成格式合法的占位 URL 就好了，测试里不会真的发请求。
 */
@SpringBootTest(properties = {
        // 覆盖 SPRING_DATASOURCE_URL 环境变量，强制使用 H2 内存库
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
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractWebIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    /**
     * 使用指定账号登录并提取 Sa-Token 值，供子类测试方法携带 token 调用受保护接口。
     */
    protected String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s"
                                }
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();

        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.tokenValue");
    }

    /** 以管理员身份登录（拥有全部权限：user:profile:read, user:center:read, user:center:manage）。 */
    protected String loginAsAdmin() throws Exception {
        return loginAndGetToken("admin", "Admin@123456");
    }

    /** 以普通用户身份登录（仅有 user:profile:read 权限，无法访问用户中心管理接口）。 */
    protected String loginAsAlice() throws Exception {
        return loginAndGetToken("alice", "Alice@123456");
    }

    /** 以审计员身份登录（拥有 user:profile:read, user:center:read，无 user:center:manage）。 */
    protected String loginAsAuditor() throws Exception {
        return loginAndGetToken("auditor", "Audit@123456");
    }
}
