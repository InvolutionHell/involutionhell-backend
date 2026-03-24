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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractWebIntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    /**
     * 使用指定账号登录并提取 Sa-Token 值。
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

    /**
     * 以管理员身份登录。
     */
    protected String loginAsAdmin() throws Exception {
        return loginAndGetToken("admin", "Admin@123456");
    }

    /**
     * 以普通用户身份登录。
     */
    protected String loginAsAlice() throws Exception {
        return loginAndGetToken("alice", "Alice@123456");
    }

    /**
     * 以审计员身份登录。
     */
    protected String loginAsAuditor() throws Exception {
        return loginAndGetToken("auditor", "Audit@123456");
    }
}
