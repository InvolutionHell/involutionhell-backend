package com.involutionhell.backend.chat.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.chat.dto.ChatTurnSaveRequest;
import com.involutionhell.backend.chat.repository.ChatHistoryRepository;
import com.involutionhell.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/chat/sessions/save —— 保存一次 AI 对话回合（替代前端原 Prisma 直连）。
 *
 * 鉴权：匿名允许（SaTokenConfigure 里放行本路径）。原 Prisma 实现就是匿名也写，
 * chat.userId 允许 NULL；保持语义一致，避免前端切流量时未登录用户聊天历史丢失。
 * 如果登录了，用 sa-token 取 userId 关联；没登录就 NULL。
 *
 * 为什么单独放一个 controller 而不是塞进 OpenAiController：OpenAiController 管
 * 流式代理，这里管持久化，职责不同；而且这条路径要对匿名开放，和 OpenAI 代理
 * 的登录要求不一致，拆开配 SaToken 拦截规则更清晰。
 */
@RestController
@RequestMapping("/api/chat/sessions")
public class ChatHistoryController {

    private final ChatHistoryRepository chatHistoryRepository;

    public ChatHistoryController(ChatHistoryRepository chatHistoryRepository) {
        this.chatHistoryRepository = chatHistoryRepository;
    }

    @PostMapping("/save")
    public ApiResponse<Void> save(@RequestBody ChatTurnSaveRequest req) {
        if (req == null || req.chatId() == null || req.chatId().isBlank()) {
            return ApiResponse.fail("chatId 不能为空");
        }

        // StpUtil.isLogin() 对匿名请求返回 false 而不是抛异常——配合 SaToken
        // 拦截器在 SaTokenConfigure 里 notMatch 放行本路径，才能真正对匿名生效。
        Long userId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;

        chatHistoryRepository.saveTurn(
                req.chatId(),
                userId,
                req.userMessage(),
                req.assistantMessage());

        return ApiResponse.okMessage("saved");
    }
}
