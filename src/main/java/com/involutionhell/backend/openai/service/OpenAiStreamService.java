package com.involutionhell.backend.openai.service;

import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OpenAiStreamService {

    private final OpenAiStreamGateway gateway;
    private final ObjectMapper objectMapper;

    /**
     * 创建 OpenAI 流式服务并注入上游网关和 JSON 工具。
     */
    public OpenAiStreamService(OpenAiStreamGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建一个新的 SSE 推送通道并异步转发 OpenAI 的流式响应。
     */
    public SseEmitter stream(OpenAiStreamRequest request) {
        gateway.validateConfiguration(request);

        SseEmitter emitter = new SseEmitter(0L);
        OpenAiEventSink sink = new SseEmitterOpenAiEventSink(emitter);
        Thread.ofVirtual()
                .name("openai-sse-", 0)
                .start(() -> streamToSink(request, sink));
        return emitter;
    }

    /**
     * 将单次 OpenAI 流式请求转发到指定事件下游。
     */
    void streamToSink(OpenAiStreamRequest request, OpenAiEventSink sink) {
        try (InputStream inputStream = gateway.openStream(request)) {
            relayEvents(inputStream, sink);
            sink.complete();
        } catch (Exception exception) {
            handleStreamError(exception, sink);
        }
    }

    /**
     * 解析 OpenAI 返回的 SSE 文本流并逐条转发给前端。
     */
    void relayEvents(InputStream inputStream, OpenAiEventSink sink) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            String currentEventName = null;
            StringBuilder currentData = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    if (dispatchEvent(currentEventName, currentData, sink)) {
                        return;
                    }
                    currentEventName = null;
                    currentData.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    currentEventName = line.substring("event:".length()).trim();
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (currentData.length() > 0) {
                        currentData.append('\n');
                    }
                    currentData.append(line.substring("data:".length()).stripLeading());
                }
            }

            dispatchEvent(currentEventName, currentData, sink);
        }
    }

    /**
     * 发送单条已组装完成的 SSE 事件，并在完成事件出现时终止读取循环。
     */
    private boolean dispatchEvent(String explicitEventName, StringBuilder dataBuffer, OpenAiEventSink sink)
            throws IOException {
        if (dataBuffer.length() == 0) {
            return false;
        }

        String payload = dataBuffer.toString();
        if ("[DONE]".equals(payload)) {
            sink.send("done", payload);
            return true;
        }

        String eventName = resolveEventName(explicitEventName, payload);
        sink.send(eventName, payload);
        return "response.completed".equals(eventName) || "response.failed".equals(eventName);
    }

    /**
     * 从 OpenAI 返回的 JSON 负载中推断事件名，显式事件名优先。
     */
    private String resolveEventName(String explicitEventName, String payload) {
        if (StringUtils.hasText(explicitEventName)) {
            return explicitEventName;
        }
        try {
            JsonNode jsonNode = objectMapper.readTree(payload);
            if (jsonNode.hasNonNull("type")) {
                return jsonNode.get("type").asText();
            }
        } catch (JacksonException ignored) {
            // 如果当前数据不是 JSON，则退回到默认 SSE 事件名。
        }
        return "message";
    }

    /**
     * 将上游异常转换为 SSE error 事件，并正确结束当前推送。
     */
    private void handleStreamError(Exception exception, OpenAiEventSink sink) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        try {
            sink.send("error", serializeError(exception));
        } catch (IOException ignored) {
            // 下游连接已断开时不再重复抛错。
        }
        sink.completeWithError(exception);
    }

    /**
     * 把异常信息包装为统一的 JSON 文本，便于前端直接消费。
     */
    private String serializeError(Exception exception) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "message",
                    StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "OpenAI 流式调用失败"
            ));
        } catch (JacksonException jsonProcessingException) {
            return "{\"message\":\"OpenAI 流式调用失败\"}";
        }
    }

    private static final class SseEmitterOpenAiEventSink implements OpenAiEventSink {

        private final SseEmitter emitter;

        /**
         * 使用 Spring MVC 的 SseEmitter 适配下游事件发送能力。
         */
        private SseEmitterOpenAiEventSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        /**
         * 发送一条带事件名的 SSE 消息。
         */
        @Override
        public void send(String eventName, String data) throws IOException {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        }

        /**
         * 正常完成当前 SSE 响应。
         */
        @Override
        public void complete() {
            emitter.complete();
        }

        /**
         * 以异常状态结束当前 SSE 响应。
         */
        @Override
        public void completeWithError(Throwable throwable) {
            emitter.completeWithError(throwable);
        }
    }
}
