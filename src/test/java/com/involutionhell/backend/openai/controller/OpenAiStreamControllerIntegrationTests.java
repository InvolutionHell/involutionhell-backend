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

/**
 * OpenAiStreamController 集成测试。
 *
 * 旧测试有三个问题：
 *
 * 1. 请求体字段写错了。DTO 已从单字段 message 改为多轮对话列表 messages，
 *    旧测试还发 {"message":"..."}, 服务端 messages 为空，直接 400 校验失败，根本进不了 SSE 流程。
 *
 * 2. 匿名请求的期望消息过时了。未带 token 是 Sa-Token NOT_TOKEN 场景，
 *    GlobalExceptionHandler 现在返回 "未提供 Token"，不是旧版的通用文案。
 *
 * 3. StreamingResponseBody 不能用轮询 getContentAsString() 来读响应体。
 *    Spring 把实际写入分派到异步线程，MockMvc 必须用两步走：
 *    先 perform + asyncStarted 拿到 MvcResult，再 perform(asyncDispatch(mvcResult)) 才能读到内容。
 *    旧版轮询方式始终读到空，超时报错。
 */
@Import(OpenAiStreamControllerIntegrationTests.OpenAiTestConfiguration.class)
class OpenAiStreamControllerIntegrationTests extends AbstractWebIntegrationTest {

    @Test
    void streamReturnsSseEventsForAuthenticatedUser() throws Exception {
        String token = loginAsAdmin();

        // 第一步：发起请求，确认异步处理已启动（StreamingResponseBody 异步写入）
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

        // 第二步：触发异步派发，此时响应体才真正写入，relayEvents() 把内容转成 0:"hello"\n 格式
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

        // 用 stub 替换真实 Gateway，避免集成测试依赖外部 OpenAI 服务
        @Bean
        @Primary
        OpenAiStreamGateway openAiStreamGateway() {
            return new StubOpenAiStreamGateway();
        }
    }

    private static final class StubOpenAiStreamGateway implements OpenAiStreamGateway {

        @Override
        public void validateConfiguration(OpenAiStreamRequest request) {
        }

        // 必须用 OpenAI 实际的 SSE 格式，relayEvents() 从 choices[0].delta.content 读文本。
        // 旧 stub 用的是自定义 {"type":"...","delta":"..."} 格式，relayEvents() 解析不到，输出为空。
        @Override
        public InputStream openStream(OpenAiStreamRequest request) {
            return new ByteArrayInputStream("""
                    data: {"choices":[{"delta":{"content":"hello"}}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8));
        }
    }
}
