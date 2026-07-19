package com.involutionhell.backend.usercenter.repository;

import com.involutionhell.backend.usercenter.model.UserIdentity;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 基于 Spring JDBC 的登录身份仓库实现，读写 user_identities 表。
 */
@Repository
public class JdbcUserIdentityRepository implements UserIdentityRepository {

    private final JdbcTemplate jdbc;

    private final RowMapper<UserIdentity> rowMapper = (rs, rowNum) -> new UserIdentity(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("provider"),
            rs.getString("provider_user_id"),
            rs.getString("email_at_link"),
            rs.getString("display_name_at_link"),
            toInstant(rs.getTimestamp("linked_at")),
            toInstant(rs.getTimestamp("last_login_at"))
    );

    public JdbcUserIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    // provider 在仓库入口统一小写：JustAuth 的 source 名是大写（"GITHUB"），
    // 而表存小写（CHECK 约束）。不归一化的话查询侧静默查空 → 老用户被当新用户建号。
    private static String normalize(String provider) {
        return provider == null ? null : provider.toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public Optional<UserIdentity> findByProviderAndProviderUserId(String provider, String providerUserId) {
        List<UserIdentity> rows = jdbc.query(
                "SELECT * FROM user_identities WHERE provider = ? AND provider_user_id = ?",
                rowMapper, normalize(provider), providerUserId);
        return rows.stream().findFirst();
    }

    @Override
    public List<UserIdentity> findByUserId(long userId) {
        return jdbc.query(
                "SELECT * FROM user_identities WHERE user_id = ? ORDER BY linked_at",
                rowMapper, userId);
    }

    @Override
    public UserIdentity insert(UserIdentity identity) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO user_identities (user_id, provider, provider_user_id, email_at_link, display_name_at_link) " +
                            "VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, identity.userId());
            ps.setString(2, normalize(identity.provider()));
            ps.setString(3, identity.providerUserId());
            ps.setString(4, identity.emailAtLink());
            ps.setString(5, identity.displayNameAtLink());
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return jdbc.queryForObject("SELECT * FROM user_identities WHERE id = ?", rowMapper, id);
    }

    @Override
    public void touchLastLogin(long id) {
        jdbc.update("UPDATE user_identities SET last_login_at = CURRENT_TIMESTAMP WHERE id = ?", id);
    }
}
