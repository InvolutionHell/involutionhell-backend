package com.involutionhell.backend.posts.repository;

import com.involutionhell.backend.posts.dto.PostSummaryView;
import com.involutionhell.backend.posts.model.Post;
import com.involutionhell.backend.posts.model.PostStatus;
import com.involutionhell.backend.posts.model.PostVisibility;
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
 * posts 表的 Spring JDBC 实现，镜像 JdbcSharedLinkRepository 的范式。
 *
 * tags 列：Postgres 侧 JSONB，H2 测试侧 VARCHAR。
 * 读写均用 setObject(Types.OTHER) + JSON 字符串，与 JdbcSharedLinkRepository.flags 一致。
 *
 * 时间戳：统一用 Timestamp → Instant 转换，避免时区差异。
 */
@Repository
public class JdbcPostRepository implements PostRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcPostRepository.class);

    /** tags JSONB 反序列化目标类型。 */
    private static final TypeReference<List<String>> TAGS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcPostRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** RowMapper：将 ResultSet 一行映射为 Post record。 */
    private final RowMapper<Post> rowMapper = (rs, rowNum) -> new Post(
            rs.getLong("id"),
            rs.getLong("author_id"),
            rs.getString("slug"),
            rs.getString("title"),
            rs.getString("description"),
            parseTags(rs.getString("tags")),
            rs.getString("content_md"),
            rs.getString("cover_url"),
            rs.getString("visibility"),
            rs.getString("status"),
            rs.getString("promoted_pr_url"),
            toInstant(rs.getTimestamp("promoted_at")),
            rs.getInt("view_count"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    @Override
    public Post insert(Post draft) {
        KeyHolder kh = new GeneratedKeyHolder();
        String sql = "INSERT INTO posts "
                + "(author_id, slug, title, description, tags, content_md, cover_url, visibility, status) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, draft.authorId());
            ps.setString(2, draft.slug());
            ps.setString(3, draft.title());
            // description 可空
            if (draft.description() == null) ps.setNull(4, Types.VARCHAR);
            else ps.setString(4, draft.description());
            // tags 走 JSONB / VARCHAR 兼容写法
            ps.setObject(5, serializeTags(draft.tags()), Types.OTHER);
            ps.setString(6, draft.contentMd());
            // cover_url 可空
            if (draft.coverUrl() == null) ps.setNull(7, Types.VARCHAR);
            else ps.setString(7, draft.coverUrl());
            ps.setString(8, draft.visibility() != null ? draft.visibility() : PostVisibility.PUBLIC);
            ps.setString(9, draft.status()     != null ? draft.status()     : PostStatus.PUBLISHED);
            return ps;
        }, kh);
        Long id = kh.getKey() != null ? kh.getKey().longValue() : null;
        return findById(id).orElseThrow(() -> new IllegalStateException("insert returned no row"));
    }

    @Override
    public Optional<Post> findById(Long id) {
        if (id == null) return Optional.empty();
        return jdbc.query("SELECT * FROM posts WHERE id = ?", rowMapper, id)
                   .stream().findFirst();
    }

    @Override
    public Optional<Post> findByAuthorAndSlug(Long authorId, String slug) {
        return jdbc.query(
                "SELECT * FROM posts WHERE author_id = ? AND slug = ?",
                rowMapper, authorId, slug)
                .stream().findFirst();
    }

    @Override
    public List<Post> findByAuthor(Long authorId) {
        return jdbc.query(
                "SELECT * FROM posts WHERE author_id = ? ORDER BY created_at DESC",
                rowMapper, authorId);
    }

    @Override
    public List<PostSummaryView> findFeedWithAuthor(int limit, int offset) {
        // p.* 取回 posts 全列（rowMapper 需要 content_md 等全字段），
        // 再追加 3 个作者别名列，LEFT JOIN user_accounts 一次消除 N+1
        String sql = "SELECT p.*, "
                + "u.username AS author_username, "
                + "u.display_name AS author_display_name, "
                + "u.avatar_url AS author_avatar_url "
                + "FROM posts p "
                + "LEFT JOIN user_accounts u ON u.id = p.author_id "
                + "WHERE p.status = ? AND p.visibility = ? "
                + "ORDER BY p.created_at DESC LIMIT ? OFFSET ?";
        return jdbc.query(sql, (rs, rowNum) -> {
            Post p = rowMapper.mapRow(rs, rowNum);
            String username    = rs.getString("author_username");
            String displayName = rs.getString("author_display_name");
            String avatarUrl   = rs.getString("author_avatar_url");
            return PostSummaryView.from(p,
                    username    != null ? username    : "unknown",
                    displayName != null ? displayName : "",
                    avatarUrl);
        }, PostStatus.PUBLISHED, PostVisibility.PUBLIC, limit, offset);
    }

    @Override
    public int countByAuthorAndSlugPrefix(Long authorId, String slugPrefix) {
        // 查询 slug = slugPrefix 或 slug LIKE slugPrefix-{数字}，用于生成唯一后缀
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE author_id = ? AND (slug = ? OR slug LIKE ?)",
                Integer.class,
                authorId, slugPrefix, slugPrefix + "-%");
        return n != null ? n : 0;
    }

    @Override
    public int update(Long id, String slug, String title, String description,
                      String tagsJson, String contentMd, String coverUrl) {
        // 返回受影响行数；0 表示 id 不存在（被并发删除）
        return jdbc.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE posts SET slug = ?, title = ?, description = ?, tags = ?, "
                            + "content_md = ?, cover_url = ?, updated_at = NOW() WHERE id = ?");
            ps.setString(1, slug);
            ps.setString(2, title);
            if (description == null) ps.setNull(3, Types.VARCHAR);
            else ps.setString(3, description);
            // tagsJson 已是 JSON 字符串（由 service 层序列化传入）
            ps.setObject(4, tagsJson, Types.OTHER);
            ps.setString(5, contentMd);
            if (coverUrl == null) ps.setNull(6, Types.VARCHAR);
            else ps.setString(6, coverUrl);
            ps.setLong(7, id);
            return ps;
        });
    }

    @Override
    public void delete(Long id) {
        jdbc.update("DELETE FROM posts WHERE id = ?", id);
    }

    @Override
    public void markPromoted(Long id, String prUrl) {
        jdbc.update(
                "UPDATE posts SET promoted_pr_url = ?, promoted_at = NOW(), updated_at = NOW() "
                        + "WHERE id = ?",
                prUrl, id);
    }

    // ========== 私有工具方法 ==========

    /** 将 List<String> 序列化为 JSON 字符串，写入 JSONB 列。 */
    private String serializeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception e) {
            log.warn("serialize tags failed, falling back to empty array: {}", e.getMessage());
            return "[]";
        }
    }

    /** 将 JSONB 列读出的 JSON 字符串反序列化为 List<String>。 */
    private List<String> parseTags(String json) {
        if (json == null || json.isEmpty()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, TAGS_TYPE);
        } catch (Exception e) {
            log.warn("parse tags failed, returning empty list: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Timestamp → Instant，null 安全。 */
    private Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
