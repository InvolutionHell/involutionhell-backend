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
 * <h3>三处改动说明</h3>
 *
 * <h4>1. 请求体字段：message → messages</h4>
 * <p>{@link OpenAiStreamRequest} DTO 已从单条字符串字段 {@code message} 重构为
 * 多轮对话列表字段 {@code messages}，以对齐 Vercel AI SDK 的 payload 格式。
 * 旧版测试发送 {@code {"message":"..."}}，服务端将 {@code messages} 视为 null/空，
 * 触发 {@code @NotEmpty} 校验失败（400），而非进入 SSE 处理流程。</p>
 *
 * <h4>2. 匿名请求错误消息："未登录..." → "未提供 Token"</h4>
 * <p>与 AuthControllerIntegrationTests 同理，未携带 token 属于 Sa-Token {@code NOT_TOKEN}
 * 场景，GlobalExceptionHandler 的当前输出是 "未提供 Token"，
 * 旧版通用消息已过时。</p>
 *
 * <h4>3. StreamingResponseBody 的 MockMvc 测试方式：asyncDispatch 模式</h4>
 * <p>控制器返回 {@link org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody}，
 * Spring 将实际写入操作分派到异步线程。MockMvc 对此类异步响应的正确测试步骤是：
 * <ol>
 *   <li>调用 {@code mockMvc.perform(...).andExpect(request().asyncStarted()).andReturn()}
 *       触发异步处理，获取 {@code MvcResult}；</li>
 *   <li>再调用 {@code mockMvc.perform(asyncDispatch(mvcResult))} 完成派发，
 *       此时响应体才真正写入，可对 status / content 做断言。</li>
 * </ol>
 * 旧版使用轮询 {@code getContentAsString()} 的方式无法获取到 {@code StreamingResponseBody}
 * 写入的内容，测试超时报错 "SSE 响应内容未按预期写入"。</p>
 */
@Import(OpenAiStreamControllerIntegrationTests.OpenAiTestConfiguration.class)
class OpenAiStreamControllerIntegrationTests extends AbstractWebIntegrationTest {

    /**
     * 验证已登录用户可以发起 SSE 流式请求，并收到 Vercel Stream 格式的响应。
     *
     * <p>采用 asyncDispatch 两步模式：先启动异步，再派发获取完整响应体。</p>
     */
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

        // 第二步：触发异步派发，断言响应体包含 Vercel Stream 前导符 "0:" 和内容 "hello"
        // relayEvents() 从 choices[0].delta.content 提取文本，转换为 0:"<text>"\n 格式
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("0:")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hello")));
    }

    /**
     * 未携带 token 访问流式接口，SaInterceptor 在请求到达控制器前即触发 NOT_TOKEN 异常，
     * 返回 401 + "未提供 Token"，不会进入异步处理流程。
     */
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

    /**
     * messages 为空数组时，@NotEmpty 校验失败，返回 400 + 字段级错误消息。
     * 旧测试发送 {"message": ""} 并期望 "message: 消息不能为空"，已按新 DTO 结构更新。
     */
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
         * 替换真实 {@link OpenAiStreamGateway}，避免集成测试依赖外部 OpenAI 服务或 Mockito。
         * {@code @Primary} 确保此 Bean 在有多个同类型 Bean 时优先被注入。
         */
        @Bean
        @Primary
        OpenAiStreamGateway openAiStreamGateway() {
            return new StubOpenAiStreamGateway();
        }
    }

    private static final class StubOpenAiStreamGateway implements OpenAiStreamGateway {

        /** 测试环境无需校验 OpenAI 配置（apiKey 等），直接放行。 */
        @Override
        public void validateConfiguration(OpenAiStreamRequest request) {
        }

        /**
         * 返回一条符合 OpenAI SSE 协议格式的固定响应，供 {@code relayEvents()} 解析。
         *
         * <p>{@code relayEvents()} 从 {@code choices[0].delta.content} 提取文本，
         * 转换为 {@code 0:"hello"\n} 写入输出流。
         * 旧版 stub 使用 {@code {"type":"...","delta":"..."}} 格式，
         * 与 OpenAI 实际格式不符，{@code relayEvents()} 无法找到 {@code choices} 节点，
         * 导致输出流为空，asyncDispatch 后 content 断言失败。</p>
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
