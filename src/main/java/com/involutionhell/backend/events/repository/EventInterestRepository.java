package com.involutionhell.backend.events.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * event_interests 表的数据访问。语义和 FollowService 类似——
 * "感兴趣"比 RSVP 门槛低：用户点一下表明关注，不承诺出席；取消也是一次点击。
 *
 * 为什么不用独立 service 类：逻辑很薄（3 个方法），做成 Repository 就够，
 * Controller 直接注入调用，避免为了"分层"而分层。
 */
@Repository
public class EventInterestRepository {

    private final JdbcTemplate jdbc;

    public EventInterestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 添加感兴趣记录。幂等：同一 (event, user) 已存在时不报错。 */
    public void add(long eventId, long userId) {
        try {
            jdbc.update(
                    "INSERT INTO event_interests (event_id, user_id, created_at) VALUES (?, ?, NOW()) "
                            + "ON CONFLICT (event_id, user_id) DO NOTHING",
                    eventId, userId);
        } catch (DuplicateKeyException ignored) {
            // H2 或其他驱动可能走 DuplicateKey 分支，一起吞掉保持幂等
        }
    }

    /** 取消感兴趣。记录不存在也 return 0，不报错。 */
    public void remove(long eventId, long userId) {
        jdbc.update(
                "DELETE FROM event_interests WHERE event_id = ? AND user_id = ?",
                eventId, userId);
    }

    /** 某活动当前有多少人表达了兴趣。前端详情页显示 "23 人感兴趣"。 */
    public long countByEvent(long eventId) {
        Long cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_interests WHERE event_id = ?",
                Long.class, eventId);
        return cnt != null ? cnt : 0L;
    }

    /** 当前登录用户是否对某活动感兴趣。匿名调用方需自己短路 false，不要调这个。 */
    public boolean isInterested(long eventId, long userId) {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_interests WHERE event_id = ? AND user_id = ?",
                Integer.class, eventId, userId);
        return cnt != null && cnt > 0;
    }

    /** 某用户感兴趣的活动 id 列表。个人主页 "我关注的活动" 区块用。 */
    public List<Long> findEventIdsByUser(long userId) {
        return jdbc.query(
                "SELECT event_id FROM event_interests WHERE user_id = ? ORDER BY created_at DESC",
                (rs, rn) -> rs.getLong("event_id"),
                userId);
    }
}
