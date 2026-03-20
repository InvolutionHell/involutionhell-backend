package com.involutionhell.backend.openai.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import com.involutionhell.backend.openai.service.OpenAiStreamGateway;
import com.involutionhell.backend.openai.service.OpenAiStreamService;
import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MvcResult;

@Import(OpenAiStreamControllerIntegrationTests.OpenAiTestConfiguration.class)
class OpenAiStreamControllerIntegrationTests extends AbstractWebIntegrationTest {

    @Test
    void streamReturnsSseEventsForAuthenticatedUser() throws Exception {
        String token = loginAsAdmin();
        MvcResult mvcResult = mockMvc.perform(post("/api/openai/responses/stream")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "你好"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        waitForSseBody(mvcResult);

        Assertions.assertThat(mvcResult.getResponse().getStatus()).isEqualTo(200);
        Assertions.assertThat(mvcResult.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        Assertions.assertThat(mvcResult.getResponse().getContentAsString()).contains("response.output_text.delta");
        Assertions.assertThat(mvcResult.getResponse().getContentAsString()).contains("response.completed");
    }

    @Test
    void streamRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(post("/api/openai/responses/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "你好"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("未登录或登录状态已失效"));
    }

    @Test
    void streamValidatesBlankMessage() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(post("/api/openai/responses/stream")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("message: 消息不能为空"));
    }

    /**
     * 等待模拟的 SSE 推送线程把事件内容写入响应体。
     */
    private void waitForSseBody(MvcResult mvcResult) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (!mvcResult.getResponse().getContentAsString().isBlank()) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException("SSE 响应内容未按预期写入");
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
         * 返回固定的 SSE 事件流，供控制器测试验证输出格式。
         */
        @Override
        public InputStream openStream(OpenAiStreamRequest request) {
            return new ByteArrayInputStream("""
                    data: {"type":"response.output_text.delta","delta":"hello"}

                    data: {"type":"response.completed"}

                    """.getBytes(StandardCharsets.UTF_8));
        }
    }
}
