package com.involutionhell.backend.chat.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.chat.dto.ChatTurnSaveRequest;
import com.involutionhell.backend.chat.repository.ChatHistoryRepository;
import com.involutionhell.backend.chat.repository.ChatOwner;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.common.error.AccessDeniedBusinessException;
import java.util.Optional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/chat/sessions/save —— 保存一次 AI 对话回合（替代前端原 Prisma 直连）。
 *
 * 鉴权策略（INV-002）：匿名 + 登录都允许，但写入前必须做归属校验：
 *   - chat 不存在：允许任意调用方创建（首次写入）
 *   - chat 存在且 ownerId == null：允许匿名继续写、登录用户首次"接管"为 owner
 *   - chat 存在且 ownerId != null：调用方必须登录且 userId 与 ownerId 完全匹配
 *
 * 攻击场景：攻击者拿到他人 chatId（前端 log / share URL leak），匿名 POST
 * 即可往 victim 历史里塞消息。COALESCE 语义不会改 ownerId，但 INSERT INTO
 * "Message" 已经发生——这是数据完整性 + 内容污染问题。
 *
 * 防御深度：controller 层前置校验（快速拦截）+ repository 层 SQL WHERE 子句
 * 原子校验（fix #27 TOCTOU，消除 lookupOwner 与 saveTurn 之间的竞态窗口）。
 *
 * 见 SecurityInvariantsTests INV-002 三条断言。
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
        Long callerUserId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;

        // INV-002：归属校验。已绑定 owner 的 chat 必须由 owner 本人写。
        // 这一步必须在 saveTurn 之前——saveTurn 内部用 ON CONFLICT upsert，
        // 一旦执行就会插入 Message 行，事后回滚得靠 @Transactional，宁可前置拦截。
        // SQL WHERE 子句兜底 TOCTOU（fix #27）。
        Optional<ChatOwner> existing = chatHistoryRepository.lookupOwner(req.chatId());
        if (existing.isPresent() && !existing.get().isAnonymous()) {
            Long ownerId = existing.get().ownerId();
            if (callerUserId == null || !callerUserId.equals(ownerId)) {
                throw new AccessDeniedBusinessException("不允许写入他人的 chat 历史");
            }
        }

        chatHistoryRepository.saveTurn(
                req.chatId(),
                callerUserId,
                req.userMessage(),
                req.assistantMessage());

        return ApiResponse.okMessage("saved");
    }
}
