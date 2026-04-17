package com.involutionhell.backend.events.repository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final NamedParameterJdbcTemplate namedJdbc;

    public EventInterestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 批量 count 用 named parameter 的 IN 子句，比自己拼 "?,?,?" 更安全
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
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

    /**
     * 批量统计多场活动的兴趣人数，避免列表接口 N+1 查询。
     *
     * 一次 GROUP BY 查完返回 map；没出现在结果里的 event id（即兴趣人数为 0）调用方
     * 自己 getOrDefault(id, 0L) 兜底。传入空集合直接返回空 map，不打 DB。
     */
    public Map<Long, Long> countByEventIds(Collection<Long> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) return Map.of();
        Map<Long, Long> result = new HashMap<>();
        MapSqlParameterSource params = new MapSqlParameterSource("ids", eventIds);
        namedJdbc.query(
                "SELECT event_id, COUNT(*) AS cnt FROM event_interests "
                        + "WHERE event_id IN (:ids) GROUP BY event_id",
                params,
                rs -> {
                    result.put(rs.getLong("event_id"), rs.getLong("cnt"));
                });
        return result;
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
