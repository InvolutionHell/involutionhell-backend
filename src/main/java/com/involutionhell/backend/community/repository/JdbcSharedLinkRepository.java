package com.involutionhell.backend.community.repository;

import com.involutionhell.backend.community.model.SharedLink;
import com.involutionhell.backend.community.model.SharedLinkStatus;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring JDBC 实现。flags 字段：Postgres 侧 JSONB，H2 测试侧 VARCHAR，
 * 都用 setObject(Types.OTHER) + JSON 字符串写入，与 Event.speakers 保持一致。
 */
@Repository
public class JdbcSharedLinkRepository implements SharedLinkRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcSharedLinkRepository.class);
    private static final TypeReference<Map<String, Boolean>> FLAGS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSharedLinkRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<SharedLink> rowMapper = (rs, rowNum) -> new SharedLink(
            rs.getLong("id"),
            rs.getLong("submitter_id"),
            rs.getString("url"),
            rs.getString("url_hash"),
            rs.getString("host"),
            rs.getString("recommendation"),
            rs.getString("og_title"),
            rs.getString("og_description"),
            rs.getString("og_cover"),
            rs.getString("og_site_name"),
            rs.getString("og_fetch_error"),
            rs.getString("category"),
            parseFlags(rs.getString("flags")),
            rs.getString("status"),
            rs.getInt("report_count"),
            toInstant(rs.getTimestamp("archived_at")),
            rs.getString("archived_reason"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    @Override
    public SharedLink insert(SharedLink draft) {
        KeyHolder kh = new GeneratedKeyHolder();
        String sql = "INSERT INTO shared_links "
                + "(submitter_id, url, url_hash, host, recommendation, status, flags) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)";
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, draft.submitterId());
            ps.setString(2, draft.url());
            ps.setString(3, draft.urlHash());
            ps.setString(4, draft.host());
            if (draft.recommendation() == null) ps.setNull(5, Types.VARCHAR);
            else ps.setString(5, draft.recommendation());
            ps.setString(6, draft.status() != null ? draft.status() : SharedLinkStatus.PENDING);
            ps.setString(7, serializeFlags(draft.flags()));
            return ps;
        }, kh);
        Long id = kh.getKey() != null ? kh.getKey().longValue() : null;
        return findById(id).orElseThrow(() -> new IllegalStateException("insert returned no row"));
    }

    @Override
    public Optional<SharedLink> findById(Long id) {
        if (id == null) return Optional.empty();
        return jdbc.query("SELECT * FROM shared_links WHERE id = ?", rowMapper, id)
                   .stream().findFirst();
    }

    @Override
    public Optional<SharedLink> findByUrlHash(String urlHash) {
        return jdbc.query("SELECT * FROM shared_links WHERE url_hash = ?", rowMapper, urlHash)
                   .stream().findFirst();
    }

    @Override
    public List<SharedLink> findApproved(String category, int limit, int offset) {
        StringBuilder sb = new StringBuilder("SELECT * FROM shared_links WHERE status = ? ");
        List<Object> args = new ArrayList<>();
        args.add(SharedLinkStatus.APPROVED);
        if (category != null && !category.isEmpty()) {
            sb.append("AND category = ? ");
            args.add(category);
        }
        sb.append("ORDER BY created_at DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sb.toString(), rowMapper, args.toArray());
    }

    @Override
    public List<SharedLink> findBySubmitter(Long submitterId) {
        return jdbc.query(
                "SELECT * FROM shared_links WHERE submitter_id = ? ORDER BY created_at DESC",
                rowMapper, submitterId);
    }

    @Override
    public List<SharedLink> findPendingForAdmin() {
        return jdbc.query(
                "SELECT * FROM shared_links WHERE status IN (?, ?) ORDER BY created_at ASC",
                rowMapper,
                SharedLinkStatus.PENDING_MANUAL, SharedLinkStatus.FLAGGED);
    }

    @Override
    public void updateEnrichment(Long id,
                                 String ogTitle, String ogDescription,
                                 String ogCover, String ogSiteName, String ogFetchError,
                                 String category, Map<String, Boolean> flags,
                                 String status) {
        jdbc.update(
                "UPDATE shared_links SET "
                        + "og_title = ?, og_description = ?, og_cover = ?, og_site_name = ?, "
                        + "og_fetch_error = ?, category = ?, flags = ?::jsonb, status = ?, "
                        + "updated_at = NOW() WHERE id = ?",
                ogTitle, ogDescription, ogCover, ogSiteName,
                ogFetchError, category, serializeFlags(flags), status, id);
    }

    @Override
    public void updateStatus(Long id, String status, String archivedReason) {
        if (SharedLinkStatus.ARCHIVED.equals(status)) {
            jdbc.update(
                    "UPDATE shared_links SET status = ?, archived_at = NOW(), archived_reason = ?, "
                            + "updated_at = NOW() WHERE id = ?",
                    status, archivedReason, id);
        } else {
            jdbc.update(
                    "UPDATE shared_links SET status = ?, updated_at = NOW() WHERE id = ?",
                    status, id);
        }
    }

    @Override
    public int incrementReportCount(Long id) {
        Integer n = jdbc.queryForObject(
                "UPDATE shared_links SET report_count = report_count + 1, updated_at = NOW() "
                        + "WHERE id = ? RETURNING report_count",
                Integer.class, id);
        return n != null ? n : 0;
    }

    @Override
    public int countBySubmitterSince(Long submitterId, Instant since) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shared_links WHERE submitter_id = ? AND created_at >= ?",
                Integer.class, submitterId, Timestamp.from(since));
        return n != null ? n : 0;
    }

    private String serializeFlags(Map<String, Boolean> flags) {
        try {
            return objectMapper.writeValueAsString(flags == null ? new HashMap<>() : flags);
        } catch (Exception e) {
            log.warn("serialize flags failed, falling back to empty object: {}", e.getMessage());
            return "{}";
        }
    }

    private Map<String, Boolean> parseFlags(String json) {
        if (json == null || json.isEmpty()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, FLAGS_TYPE);
        } catch (Exception e) {
            log.warn("parse flags failed, returning empty map: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
