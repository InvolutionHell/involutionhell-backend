package com.involutionhell.backend.chat.repository;

import java.sql.Types;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 基于 Spring JDBC 的 Chat / Message 表读写实现。
 *
 * 表结构由 Prisma 历史管理，列名是驼峰并加了双引号（"userId"、"chatId"、
 * "createdAt"），PostgreSQL 下这些名字都区分大小写，所有 SQL 里必须保留双
 * 引号——漏一个就会报 column "userid" does not exist。
 */
@Repository
public class JdbcChatHistoryRepository implements ChatHistoryRepository {

    private final JdbcTemplate jdbc;

    public JdbcChatHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 为什么用 ON CONFLICT 做 upsert 而不是先 select 再 insert：
     *   1. 单次 round-trip，少一次 SQL 调用
     *   2. 并发两个 tab 同一 chatId 时不会先后撞主键冲突报 500
     *
     * 为什么 userId 用 COALESCE：匿名请求 → 后续登录请求会复用同一个 chatId，
     * 第二次带着真实 userId 过来时应该把之前的 NULL 覆盖掉；但如果这次匿名、
     * 上次已经登录了，不能把 userId 擦掉——所以用 COALESCE(EXCLUDED.userId, "Chat"."userId")
     * 的语义：新值优先，新值为 NULL 时保留旧值。
     */
    @Override
    @Transactional
    public void saveTurn(String chatId, Long userId, String userMessage, String assistantMessage) {
        jdbc.update(
                """
                INSERT INTO "Chat" (id, "userId", "createdAt", "updatedAt")
                VALUES (?, ?, NOW(), NOW())
                ON CONFLICT (id) DO UPDATE SET
                    "userId"    = COALESCE(EXCLUDED."userId", "Chat"."userId"),
                    "updatedAt" = NOW()
                """,
                ps -> {
                    ps.setString(1, chatId);
                    if (userId == null) {
                        ps.setNull(2, Types.INTEGER);
                    } else {
                        ps.setInt(2, userId.intValue());
                    }
                });

        if (userMessage != null && !userMessage.isBlank()) {
            insertMessage(chatId, "user", userMessage);
        }
        if (assistantMessage != null && !assistantMessage.isBlank()) {
            insertMessage(chatId, "assistant", assistantMessage);
        }
    }

    private void insertMessage(String chatId, String role, String content) {
        jdbc.update(
                """
                INSERT INTO "Message" (id, "chatId", role, content, "createdAt")
                VALUES (?, ?, ?, ?, NOW())
                """,
                UUID.randomUUID().toString(),
                chatId,
                role,
                content);
    }
}
