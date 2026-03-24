package com.involutionhell.backend.usercenter.repository;

import com.involutionhell.backend.usercenter.model.UserAccount;

import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 基于 Spring JDBC 的用户账号仓库实现，读写 user_accounts 表。
 */
@Repository
public class JdbcUserAccountRepository implements UserAccountRepository {

    private final JdbcTemplate jdbc;

    /**
     * 将数据库行映射为 UserAccount 记录。
     * roles / permissions 以逗号分隔字符串存储，空字符串对应空集合。
     */
    private static final RowMapper<UserAccount> ROW_MAPPER = (rs, rowNum) -> new UserAccount(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getBoolean("enabled"),
            parseSet(rs.getString("roles")),
            parseSet(rs.getString("permissions"))
    );

    public JdbcUserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UserAccount> findById(Long id) {
        List<UserAccount> results = jdbc.query(
                "SELECT * FROM user_accounts WHERE id = ?", ROW_MAPPER, id);
        return results.stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> results = jdbc.query(
                "SELECT * FROM user_accounts WHERE username = ?", ROW_MAPPER, username);
        return results.stream().findFirst();
    }

    @Override
    public List<UserAccount> findAll() {
        return jdbc.query("SELECT * FROM user_accounts ORDER BY id", ROW_MAPPER);
    }

    @Override
    public UserAccount updateAuthorization(Long userId, Set<String> roles, Set<String> permissions) {
        jdbc.update(
                "UPDATE user_accounts SET roles = ?, permissions = ? WHERE id = ?",
                joinSet(roles), joinSet(permissions), userId);
        return findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
    }

    @Override
    public UserAccount insert(UserAccount userAccount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String sql = "INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
                     
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, userAccount.username());
            ps.setString(2, userAccount.passwordHash());
            ps.setString(3, userAccount.displayName());
            ps.setBoolean(4, userAccount.enabled());
            ps.setString(5, joinSet(userAccount.roles()));
            ps.setString(6, joinSet(userAccount.permissions()));
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入用户失败，无法获取生成的 ID");
        }
        
        return new UserAccount(
                key.longValue(),
                userAccount.username(),
                userAccount.passwordHash(),
                userAccount.displayName(),
                userAccount.enabled(),
                userAccount.roles(),
                userAccount.permissions()
        );
    }

    /**
     * 将逗号分隔字符串解析为集合，空串返回空集合。
     */
    private static Set<String> parseSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(value.split(",")));
    }

    /**
     * 将集合序列化为逗号分隔字符串，空集合返回空串。
     */
    private static String joinSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join(",", values);
    }
}