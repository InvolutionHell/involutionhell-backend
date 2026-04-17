package com.involutionhell.backend.chat.dto;

/**
 * POST /api/chat/sessions/save 的请求体。
 *
 * 由前端 app/api/chat/route.ts 的 streamText onFinish 回调在一次 AI 回合结束后
 * 调用，把 chat 会话记录 + 本轮 user 消息 + 本轮 assistant 消息一次性塞给后端持
 * 久化。合并成一次请求而不是三次是为了：
 *   1. 少两次网络往返（onFinish 阻塞流返回对用户体感没影响，但链路越短越抗抖动）
 *   2. 后端一个事务，避免 chat 写成功但消息丢的错峰状态
 *
 * 字段语义：
 *   - chatId：前端 crypto.randomUUID() 生成，首次为新会话、后续为已有会话
 *   - userMessage：本轮用户输入的纯文本（从 UIMessage.parts 拼接出）；空/缺省
 *     表示本轮无用户输入（比如从旧 chatId 恢复状态时），跳过插入
 *   - assistantMessage：本轮 AI 返回的纯文本；空表示流式失败或空响应，跳过插入
 */
public record ChatTurnSaveRequest(
        String chatId,
        String userMessage,
        String assistantMessage
) {}
