package com.involutionhell.backend.openai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import com.involutionhell.backend.openai.service.OpenAiStreamService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/openai")
public class OpenAiStreamController {

    private final OpenAiStreamService openAiStreamService;

    /**
     * 创建 OpenAI 流式控制器并注入流式服务。
     */
    public OpenAiStreamController(OpenAiStreamService openAiStreamService) {
        this.openAiStreamService = openAiStreamService;
    }

    /**
     * 调用 OpenAI Responses API 并以 SSE 形式持续推送模型输出。
     */
    @SaCheckLogin
    @PostMapping(
            path = "/responses/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamResponses(@Valid @RequestBody OpenAiStreamRequest request) {
        return openAiStreamService.stream(request);
    }
}
