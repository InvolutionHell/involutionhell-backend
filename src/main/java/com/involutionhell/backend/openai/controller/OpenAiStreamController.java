package com.involutionhell.backend.openai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.openai.dto.OpenAiStreamRequest;
import com.involutionhell.backend.openai.service.OpenAiStreamRateLimiter;
import com.involutionhell.backend.openai.service.OpenAiStreamService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/openai")
public class OpenAiStreamController {

    private final OpenAiStreamService openAiStreamService;
    private final OpenAiStreamRateLimiter rateLimiter;

    public OpenAiStreamController(
            OpenAiStreamService openAiStreamService, OpenAiStreamRateLimiter rateLimiter) {
        this.openAiStreamService = openAiStreamService;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 流式对话核心路由，供前端 Vercel SDK 直接转发。
     * 采用 TEXT_PLAIN_VALUE 返回纯文本流，抛弃了 Spring 的 SseEmitter，以完全符合 Vercel Stream Data
     * 协议要求。
     */
    @SaCheckLogin
    @PostMapping(path = "/responses/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<StreamingResponseBody> streamResponses(@Valid @RequestBody OpenAiStreamRequest request) {
        // INV-006：付费 LLM 端点必须限流。@SaCheckLogin 只挡未登录，
        // 登录用户绕过 Next.js 直 curl 后端仍会被这里的每用户窗口拦住（#297）
        rateLimiter.checkOrThrow(StpUtil.getLoginIdAsLong());
        /*
         * ============================
         * 🗑️ 被废弃的旧版方法声明留痕：
         * public SseEmitter streamResponses(@Valid @RequestBody OpenAiStreamRequest
         * request) {
         * return openAiStreamService.stream(request);
         * }
         * 上文旧版的错误：返回 SseEmitter 导致了在流失推送中总是多出 Vercel 数据不认识的 `data: ` 和 `event: ` 包裹层。
         * ============================
         */

        // 利用 StreamingResponseBody 避免额外的协议前缀干扰，实现裸文字流打字输出
        StreamingResponseBody stream = out -> {
            openAiStreamService.streamToOutputStream(request, out);
        };
        return ResponseEntity.ok()
                // 专门显式告知 Vercel AI SDK：这是最新一代的支持 Stream-Data 的端点。
                .header("X-Experimental-Stream-Data", "true")
                .body(stream);
    }
}