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
 * <h3>为什么在 @SpringBootTest 中显式指定数据源属性？</h3>
 * <p>Spring Boot 属性优先级从高到低依次为：
 * <ol>
 *   <li>{@code @SpringBootTest(properties = {...})} — 最高</li>
 *   <li>操作系统环境变量（如 {@code SPRING_DATASOURCE_URL}）</li>
 *   <li>{@code application-test.properties} 等 Profile 配置文件 — 最低</li>
 * </ol>
 * 测试服务器上的 {@code SPRING_DATASOURCE_URL} 环境变量指向生产 Neon PostgreSQL，
 * 其优先级高于 {@code application-test.properties} 中的 H2 配置，导致集成测试
 * 尝试连接 PostgreSQL，上下文启动失败，所有 Web 集成测试报错。
 * 通过在 {@code @SpringBootTest(properties)} 中覆盖数据源属性，可绕过环境变量，
 * 确保测试始终使用 H2 内存库。</p>
 *
 * <h3>为什么还要覆盖 JustAuth redirect-uri？</h3>
 * <p>JustAuth 内部使用 Apache Commons {@code UrlValidator} 校验 redirect-uri 格式。
 * 默认情况下，{@code UrlValidator} 拒绝 {@code localhost} 域名（视为非法 URL），
 * 而 {@code application.properties} 中的默认值是 {@code http://localhost:3000/...}。
 * 若不覆盖，{@code OAuthController} 在构建 {@code AuthGithubRequest} 时会立即抛出
 * {@code AuthException: Illegal redirect uri}，导致所有 OAuth 相关集成测试以 500 失败。
 * 测试环境仅需要一个格式合法的占位 URL，不会发起任何真实网络请求。</p>
 */
@SpringBootTest(properties = {
        // --- 数据源：覆盖 SPRING_DATASOURCE_URL 环境变量，强制使用 H2 内存库 ---
        "spring.datasource.url=jdbc:h2:mem:backend;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:test-schema.sql",
        // --- JustAuth：使用合法格式的占位 redirect-uri，规避 UrlValidator localhost 限制 ---
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
