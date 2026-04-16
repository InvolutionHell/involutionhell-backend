package com.involutionhell.backend.usercenter.follows;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户关注关系服务。操作 user_follows 表（follower_id, followee_id, created_at）。
 *
 * - follow / unfollow 都是幂等操作：重复 follow 不报错，unfollow 不存在的也 OK
 * - 反向查询（粉丝列表 / 关注列表）通过 JOIN user_accounts 补用户元信息，但这里
 *   只给出 id 列表和 count；元信息在 Controller 层组装 UserView 避免 Service 深度耦合
 * - 不对 follower_id / followee_id 做"用户是否存在"的前置校验：
 *   user_accounts 是 SaToken 管理的，我们信任调用方传进来的 id；
 *   如果传了个不存在的 followee_id，无非就是一条脏记录，不影响正确性
 */
@Service
public class FollowService {

    private final JdbcTemplate jdbc;

    public FollowService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 关注：幂等。同一对 (follower, followee) 已存在时不报错。
     * 用 ON CONFLICT DO NOTHING 让数据库保证原子性，避免并发竞争。
     */
    public void follow(long followerId, long followeeId) {
        if (followerId == followeeId) {
            throw new IllegalArgumentException("不能关注自己");
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO user_follows (follower_id, followee_id, created_at)
                    VALUES (?, ?, NOW())
                    ON CONFLICT (follower_id, followee_id) DO NOTHING
                    """,
                    followerId, followeeId
            );
        } catch (DuplicateKeyException ignored) {
            // 多数据库兼容：Postgres 走 ON CONFLICT 不会进这里，
            // 但其他 driver 可能抛 DuplicateKey，一起吞掉
        }
    }

    /**
     * 取消关注：幂等。记录不存在也 return 0，不报错。
     */
    public void unfollow(long followerId, long followeeId) {
        jdbc.update(
                "DELETE FROM user_follows WHERE follower_id = ? AND followee_id = ?",
                followerId, followeeId
        );
    }

    /**
     * 判断 follower 是否关注了 followee。
     */
    public boolean isFollowing(long followerId, long followeeId) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_follows WHERE follower_id = ? AND followee_id = ?",
                Integer.class,
                followerId, followeeId
        );
        return cnt != null && cnt > 0;
    }

    /**
     * 某用户的粉丝数（被多少人关注）。
     */
    public long countFollowers(long userId) {
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_follows WHERE followee_id = ?",
                Long.class,
                userId
        );
        return cnt != null ? cnt : 0;
    }

    /**
     * 某用户关注了多少人。
     */
    public long countFollowing(long userId) {
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_follows WHERE follower_id = ?",
                Long.class,
                userId
        );
        return cnt != null ? cnt : 0;
    }

    /**
     * 某用户的粉丝 id 列表，按关注时间倒序（最新关注者在前）。
     */
    public List<Long> listFollowerIds(long userId, int limit, int offset) {
        return jdbc.queryForList(
                """
                SELECT follower_id FROM user_follows
                WHERE followee_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                Long.class,
                userId, Math.min(Math.max(limit, 1), 100), Math.max(offset, 0)
        );
    }

    /**
     * 某用户关注的人 id 列表，按关注时间倒序（最近关注的在前）。
     */
    public List<Long> listFollowingIds(long userId, int limit, int offset) {
        return jdbc.queryForList(
                """
                SELECT followee_id FROM user_follows
                WHERE follower_id = ?
                ORDER BY created_at DESC
                LIMIT ? OFFSET ?
                """,
                Long.class,
                userId, Math.min(Math.max(limit, 1), 100), Math.max(offset, 0)
        );
    }
}
