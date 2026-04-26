package com.involutionhell.backend.docs.repository;

import com.involutionhell.backend.docs.model.DocContributor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * doc_contributors 表的只读访问层。
 *
 * 排行榜聚合放在 Service（内存 group by），不在 SQL 里 GROUP BY 是因为：
 *   - dailyCounts 需要按 day(yyyy-MM-dd) 二次分桶，SQL 写两次聚合不如一次拉全量再 Java 处理直观
 *   - 总行数不大（贡献者表，每个 doc × 每个贡献者一行，量级 千），全量拉回来内存压力可忽略
 */
@Repository
public class JdbcDocContributorRepository {

    private final JdbcTemplate jdbc;

    public JdbcDocContributorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final RowMapper<DocContributor> rowMapper = (rs, rowNum) -> new DocContributor(
            rs.getString("doc_id"),
            rs.getLong("github_id"),
            rs.getInt("contributions"),
            toOffsetDateTime(rs.getTimestamp("last_contributed_at")),
            toOffsetDateTime(rs.getTimestamp("created_at")),
            toOffsetDateTime(rs.getTimestamp("updated_at"))
    );

    /**
     * 拉全表。doc_contributors 是稀疏表（每个 doc × 每个贡献者一行，量级千），
     * 全量拉回来由 Service 做 group by，避免 SQL 二次聚合的复杂度。
     *
     * 显式 ORDER BY github_id, doc_id：缓存命中后输出顺序必须可重复，
     * 否则 Service 的 LinkedHashMap 插入顺序漂移 → 同一份数据反复返回不同顺序，
     * 前端 diff 检查 / 测试断言会出现假阳性失败。
     */
    public List<DocContributor> findAll() {
        return jdbc.query(
                "SELECT doc_id, github_id, contributions, last_contributed_at, created_at, updated_at "
                        + "FROM doc_contributors "
                        + "ORDER BY github_id, doc_id",
                rowMapper
        );
    }

    private static OffsetDateTime toOffsetDateTime(Timestamp ts) {
        return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
    }
}
