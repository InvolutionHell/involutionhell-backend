package com.involutionhell.backend.openai.service;

import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class OpenAiStreamService {

    private final OpenAiStreamGateway gateway;
    private final ObjectMapper objectMapper;

    public OpenAiStreamService(OpenAiStreamGateway gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
    }

    /**
     * 开启上游请求并把结果实时桥接至指定的 HTTP 响应输出流。
     */
    public void streamToOutputStream(OpenAiStreamRequest request, OutputStream outputStream) {
        /*
         * ============================
         * 🗑️ 旧版本 SseEmitter 打包人逻辑（直接导致 Vercel 不认字）：
         * public SseEmitter stream(OpenAiStreamRequest request) { ...
         * SseEmitter emitter = new SseEmitter(0L);
         * OpenAiEventSink sink = new SseEmitterOpenAiEventSink(emitter);
         * ... 开启 VirtualThread 扔到后台去 dispatchEvent()
         * return emitter;
         * ============================
         */

        gateway.validateConfiguration(request);
        try (InputStream inputStream = gateway.openStream(request)) {
            relayEvents(inputStream, outputStream);
        } catch (Exception exception) {
            handleStreamError(exception, outputStream);
        }
    }

    /**
     * 【重构核心】：这是拦截并转译第三方生硬大模型 JSON 的“文字切割手术台”。
     * 该方法提取有价值的短文本切片，将其打包为 Vercel 能识别的前置格式。
     */
    void relayEvents(InputStream inputStream, OutputStream outputStream) throws IOException {
        /*
         * ============================
         * 🗑️ 旧版纯粹转发、不对核心做精洗的代码路线：
         * String currentEventName = null;
         * StringBuilder currentData = new StringBuilder();
         * while ((line = reader.readLine()) != null) { ... 只要遇到 data 就全部原样拼接通过
         * EventEmitter 发送 ... }
         * ============================
         */

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 第三方接口返回的标准特征点都是以 "data: " 开头
                if (line.startsWith("data: ")) {
                    String payload = line.substring(6).trim();

                    // "[DONE]" 形态意味着大模型生成句号完毕，立刻跳出断开连接
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    try {
                        JsonNode jsonNode = objectMapper.readTree(payload);

                        // 深入臃肿的大树内部：直接去 choices 第一组的 delta 里面寻找关键节点唯一的 content
                        JsonNode deltaNode = jsonNode.path("choices").path(0).path("delta").path("content");

                        // 并不是所有的 JSON 都有字（第一包可能只包含角色分配）
                        if (!deltaNode.isMissingNode() && deltaNode.isTextual()) {
                            String textChunk = deltaNode.asText();
                            // ★ 最为关键的协议转译：对原生纯文本加上 Vercel Stream Text 前导符 '0:' 结合 JSON_Escaped_String 和强回车。
                            String vercelChunk = "0:" + objectMapper.writeValueAsString(textChunk) + "\n";

                            outputStream.write(vercelChunk.getBytes(StandardCharsets.UTF_8));
                            // 高频推送缓冲区，使前端能产生实时的视觉卡顿流水效果！
                            outputStream.flush();
                        }
                    } catch (Exception ignored) {
                        // 出于防守目的，故意默默捕捉并忽略空包异常，而不让流水中断。
                    }
                }
            }
        }
    }

    /**
     * 当前端断连或网络奔溃时，向流下发 Vercel 专门容错的错误前导符标识 'e:' 使得前端抛出红字。
     */
    private void handleStreamError(Exception exception, OutputStream outputStream) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        try {
            String message = StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "OpenAI Error";
            String errorChunk = "e:" + objectMapper.writeValueAsString(message) + "\n";
            outputStream.write(errorChunk.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (IOException ignored) {
        }
    }
}
