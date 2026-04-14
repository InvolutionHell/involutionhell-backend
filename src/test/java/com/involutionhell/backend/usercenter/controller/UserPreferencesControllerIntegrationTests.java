package com.involutionhell.backend.usercenter.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 用户偏好 GET/PATCH 接口集成测试。
 */
class UserPreferencesControllerIntegrationTests extends AbstractWebIntegrationTest {

    /** 未登录访问 GET /api/user-center/preferences 应返回 401。 */
    @Test
    void getPreferencesRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/api/user-center/preferences"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    /** 未登录访问 PATCH /api/user-center/preferences 应返回 401。 */
    @Test
    void patchPreferencesRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(patch("/api/user-center/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"dark\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    /**
     * 登录用户多次 PATCH 应正确合并偏好：
     * {} -> {theme:dark} -> {theme:dark, language:zh} -> {theme:light, language:zh}
     */
    @Test
    void patchPreferencesMergesCorrectly() throws Exception {
        String token = loginAsAlice();

        // 第一次：写入 theme
        mockMvc.perform(patch("/api/user-center/preferences")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"dark\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.theme").value("dark"));

        // 第二次：追加 language，theme 应保留
        mockMvc.perform(patch("/api/user-center/preferences")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"language\":\"zh\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("dark"))
                .andExpect(jsonPath("$.data.language").value("zh"));

        // 第三次：更新 theme，language 应保留
        mockMvc.perform(patch("/api/user-center/preferences")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"theme\":\"light\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("light"))
                .andExpect(jsonPath("$.data.language").value("zh"));

        // GET 验证最终状态
        mockMvc.perform(get("/api/user-center/preferences")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("light"))
                .andExpect(jsonPath("$.data.language").value("zh"));
    }
}
