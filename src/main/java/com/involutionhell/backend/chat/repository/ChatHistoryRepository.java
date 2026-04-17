package com.involutionhell.backend.chat.repository;

/**
 * AI 对话历史持久化接口。
 *
 * 为什么一个 repository 同时操作 Chat 和 Message：一次聊天回合的持久化（chat
 * upsert + user msg + assistant msg）是强业务原子性的——要么一起落库，要么
 * 不写。把三次写放在同一个 repository 里的 @Transactional 方法里，避免出现
 * "chat 有记录但消息丢了" 的错峰状态；拆成两个 repository 反而要在上层协调。
 */
public interface ChatHistoryRepository {

    /**
     * 原子地持久化一个聊天回合：
     *   1. chat 不存在则创建、存在则刷新 updatedAt 和 userId（匿名 → 登录迁移场景）
     *   2. userMessage 非空时插入一条 user role 的消息
     *   3. assistantMessage 非空时插入一条 assistant role 的消息
     *
     * 整个调用处于同一事务，任何一步异常都会回滚前面已写入的行。
     *
     * @param chatId            会话 ID，前端用 crypto.randomUUID() 生成，TEXT 主键
     * @param userId            sa-token 登录态拿到的 user_accounts.id，匿名时传 null
     * @param userMessage       本轮用户消息；null 或空字符串时跳过插入
     * @param assistantMessage  本轮 AI 回复；null 或空字符串时跳过插入
     */
    void saveTurn(String chatId, Long userId, String userMessage, String assistantMessage);
}
