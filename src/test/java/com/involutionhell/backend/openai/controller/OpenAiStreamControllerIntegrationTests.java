package com.involutionhell.backend.openai.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import com.involutionhell.backend.openai.service.OpenAiStreamGateway;
import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@Import(OpenAiStreamControllerIntegrationTests.OpenAiTestConfiguration.class)
class OpenAiStreamControllerIntegrationTests extends AbstractWebIntegrationTest {

    @Test
    void streamReturnsSseEventsForAuthenticatedUser() throws Exception {
        String token = loginAsAdmin();
        // StreamingResponseBody 采用异步派发：先获取 MvcResult，再通过 asyncDispatch 触发真实写入
        MvcResult mvcResult = mockMvc.perform(post("/openai/responses/stream")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": [{"role": "user", "content": "你好"}]
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        // relayEvents() 将 OpenAI choices[0].delta.content 转换为 Vercel Stream 格式 "0:\"hello\"\n"
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("0:")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hello")));
    }

    @Test
    void streamRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(post("/openai/responses/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": [{"role": "user", "content": "你好"}]
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("未提供 Token"));
    }

    @Test
    void streamValidatesEmptyMessages() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(post("/openai/responses/stream")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messages": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("messages: 对话历史不能为空"));
    }

    @TestConfiguration
    static class OpenAiTestConfiguration {

        /**
         * 提供一个稳定的测试桩网关，避免控制器测试依赖真实 OpenAI 或 Mockito。
         */
        @Bean
        @Primary
        OpenAiStreamGateway openAiStreamGateway() {
            return new StubOpenAiStreamGateway();
        }
    }

    private static final class StubOpenAiStreamGateway implements OpenAiStreamGateway {

        /**
         * 测试环境下跳过外部 OpenAI 配置校验。
         */
        @Override
        public void validateConfiguration(OpenAiStreamRequest request) {
        }

        /**
         * 返回固定的 OpenAI SSE 格式事件流（与真实 API 格式保持一致）。
         * relayEvents() 提取 choices[0].delta.content 写入 Vercel Stream 格式。
         */
        @Override
        public InputStream openStream(OpenAiStreamRequest request) {
            return new ByteArrayInputStream("""
                    data: {"choices":[{"delta":{"content":"hello"}}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8));
        }
    }
}
