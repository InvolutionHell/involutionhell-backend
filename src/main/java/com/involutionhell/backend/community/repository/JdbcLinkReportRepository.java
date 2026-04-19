package com.involutionhell.backend.community.repository;

import com.involutionhell.backend.community.model.LinkReport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;

@Repository
public class JdbcLinkReportRepository implements LinkReportRepository {

    private final JdbcTemplate jdbc;

    public JdbcLinkReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<LinkReport> rowMapper = (rs, rowNum) -> new LinkReport(
            rs.getLong("id"),
            rs.getLong("link_id"),
            rs.getLong("reporter_id"),
            rs.getString("reason"),
            toInstant(rs.getTimestamp("created_at"))
    );

    @Override
    public LinkReport insert(LinkReport draft) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO link_reports (link_id, reporter_id, reason) VALUES (?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, draft.linkId());
            ps.setLong(2, draft.reporterId());
            if (draft.reason() == null) ps.setNull(3, Types.VARCHAR);
            else ps.setString(3, draft.reason());
            return ps;
        }, kh);
        Long id = kh.getKey() != null ? kh.getKey().longValue() : null;
        return jdbc.query("SELECT * FROM link_reports WHERE id = ?", rowMapper, id)
                   .stream().findFirst()
                   .orElseThrow(() -> new IllegalStateException("insert returned no row"));
    }

    @Override
    public int countByLinkId(Long linkId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM link_reports WHERE link_id = ?",
                Integer.class, linkId);
        return n != null ? n : 0;
    }

    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
