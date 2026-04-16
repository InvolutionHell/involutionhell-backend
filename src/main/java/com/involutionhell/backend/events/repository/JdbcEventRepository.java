package com.involutionhell.backend.events.repository;

import com.involutionhell.backend.events.model.Event;
import com.involutionhell.backend.events.model.Event.Speaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 Spring JDBC 的 Event 仓库。
 *
 * speakers 字段：Postgres 侧是 JSONB，H2 测试侧是 VARCHAR，都用 setObject(Types.OTHER) + 字符串
 * 写入，跟 user_accounts.preferences 的处理方式保持一致。
 */
@Repository
public class JdbcEventRepository implements EventRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcEventRepository.class);
    private static final TypeReference<List<Speaker>> SPEAKER_LIST_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcEventRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 单行 → Event 映射。speakers 从 JSON 字符串解析为 List<Speaker>。 */
    private final RowMapper<Event> rowMapper = (rs, rowNum) -> new Event(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("cover_url"),
            toInstant(rs.getTimestamp("start_time")),
            toInstant(rs.getTimestamp("end_time")),
            rs.getString("discord_link"),
            rs.getString("playback_url"),
            parseSpeakers(rs.getString("speakers")),
            rs.getString("tags"),
            rs.getString("status"),
            rs.getObject("organizer_id", Long.class),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    @Override
    public Optional<Event> findById(Long id) {
        return jdbc.query("SELECT * FROM events WHERE id = ?", rowMapper, id)
                   .stream().findFirst();
    }

    @Override
    public List<Event> findPublic() {
        // NULLS LAST 让未排期活动出现在时间维度底部，而非把 null 当无穷大排前面
        return jdbc.query(
                "SELECT * FROM events WHERE status IN ('published', 'archived') "
                        + "ORDER BY start_time DESC NULLS LAST, id DESC",
                rowMapper);
    }

    @Override
    public List<Event> findAllForAdmin() {
        return jdbc.query(
                "SELECT * FROM events ORDER BY start_time DESC NULLS LAST, id DESC",
                rowMapper);
    }

    @Override
    public Event insert(Event event) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String sql = "INSERT INTO events "
                + "(title, description, cover_url, start_time, end_time, discord_link, "
                + " playback_url, speakers, tags, status, organizer_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String speakersJson = serializeSpeakers(event.speakers());

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, event.title());
            ps.setString(2, event.description() != null ? event.description() : "");
            ps.setString(3, event.coverUrl());
            ps.setObject(4, event.startTime() != null ? Timestamp.from(event.startTime()) : null);
            ps.setObject(5, event.endTime() != null ? Timestamp.from(event.endTime()) : null);
            ps.setString(6, event.discordLink());
            ps.setString(7, event.playbackUrl());
            // JSONB / VARCHAR 兼容：用 Types.OTHER 让 Postgres 驱动自动识别
            ps.setObject(8, speakersJson, Types.OTHER);
            ps.setString(9, event.tags() != null ? event.tags() : "");
            ps.setString(10, event.status() != null ? event.status() : "draft");
            ps.setObject(11, event.organizerId());
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("插入 event 失败，无法获取 id");
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("插入 event 后读不到: id=" + key));
    }

    @Override
    public Event update(Event event) {
        if (event.id() == null) throw new IllegalArgumentException("更新 event 必须带 id");
        String speakersJson = serializeSpeakers(event.speakers());
        int updated = jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "UPDATE events SET title = ?, description = ?, cover_url = ?, "
                            + "start_time = ?, end_time = ?, discord_link = ?, playback_url = ?, "
                            + "speakers = ?, tags = ?, status = ?, organizer_id = ?, "
                            + "updated_at = NOW() "
                            + "WHERE id = ?");
            ps.setString(1, event.title());
            ps.setString(2, event.description() != null ? event.description() : "");
            ps.setString(3, event.coverUrl());
            ps.setObject(4, event.startTime() != null ? Timestamp.from(event.startTime()) : null);
            ps.setObject(5, event.endTime() != null ? Timestamp.from(event.endTime()) : null);
            ps.setString(6, event.discordLink());
            ps.setString(7, event.playbackUrl());
            ps.setObject(8, speakersJson, Types.OTHER);
            ps.setString(9, event.tags() != null ? event.tags() : "");
            ps.setString(10, event.status() != null ? event.status() : "draft");
            ps.setObject(11, event.organizerId());
            ps.setLong(12, event.id());
            return ps;
        });
        if (updated == 0) throw new IllegalArgumentException("event 不存在: id=" + event.id());
        return findById(event.id())
                .orElseThrow(() -> new IllegalStateException("更新后读不到 event: id=" + event.id()));
    }

    @Override
    public void deleteById(Long id) {
        jdbc.update("DELETE FROM events WHERE id = ?", id);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    /** JSONB / VARCHAR 里存的 speakers 字符串 → List<Speaker>。 */
    private List<Speaker> parseSpeakers(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, SPEAKER_LIST_TYPE);
        } catch (Exception e) {
            log.error("解析 event.speakers JSON 失败: {}", json, e);
            // 脏数据不影响其他字段，返回空列表兜底（而不是整个 event 加载失败）
            return new ArrayList<>();
        }
    }

    /** List<Speaker> → JSON 字符串。null / 空列表都序列化为 "[]"。 */
    private String serializeSpeakers(List<Speaker> speakers) {
        if (speakers == null || speakers.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(speakers);
        } catch (Exception e) {
            log.error("序列化 speakers 失败: {}", speakers, e);
            throw new IllegalStateException("序列化 speakers 失败", e);
        }
    }
}
