package com.involutionhell.backend.chat.repository;

import com.involutionhell.backend.common.error.AccessDeniedBusinessException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
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
     * 查询 chatId 的归属信息。详细语义见接口文档。
     *
     * 实现要点：rs.getLong + rs.wasNull 才能区分"列值就是 0"和"列值是 NULL"，
     * 直接 getObject 在 H2 / Postgres 之间行为不一致。
     */
    @Override
    public Optional<ChatOwner> lookupOwner(String chatId) {
        List<ChatOwner> result = jdbc.query(
                """
                SELECT "userId" FROM "Chat" WHERE id = ?
                """,
                (rs, rn) -> {
                    long uid = rs.getLong("userId");
                    return new ChatOwner(rs.wasNull() ? null : uid);
                },
                chatId);
        return result.stream().findFirst();
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
     *
     * WHERE 子句（fix #27 TOCTOU）：归属校验在 SQL 层原子完成——ON CONFLICT
     * 命中时，只有 owner 兼容（NULL 或相同 userId）才允许 UPDATE。不兼容时
     * affected rows = 0，直接抛 AccessDeniedBusinessException，Message 不插入。
     * 消除了 controller 层 lookupOwner 与 saveTurn 之间的竞态窗口。
     */
    @Override
    @Transactional
    public void saveTurn(String chatId, Long userId, String userMessage, String assistantMessage) {
        int rows = jdbc.update(
                """
                INSERT INTO "Chat" (id, "userId", "createdAt", "updatedAt")
                VALUES (?, ?, NOW(), NOW())
                ON CONFLICT (id) DO UPDATE SET
                    "userId"    = COALESCE(EXCLUDED."userId", "Chat"."userId"),
                    "updatedAt" = NOW()
                WHERE "Chat"."userId" IS NULL OR "Chat"."userId" = EXCLUDED."userId"
                """,
                ps -> {
                    ps.setString(1, chatId);
                    if (userId == null) {
                        ps.setNull(2, Types.INTEGER);
                    } else {
                        ps.setInt(2, userId.intValue());
                    }
                });

        if (rows == 0) {
            throw new AccessDeniedBusinessException("不允许写入他人的 chat 历史");
        }

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
