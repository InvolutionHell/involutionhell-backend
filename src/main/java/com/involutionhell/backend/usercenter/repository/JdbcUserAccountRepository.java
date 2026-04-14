package com.involutionhell.backend.usercenter.repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.involutionhell.backend.usercenter.model.UserAccount;

import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final ObjectMapper objectMapper;

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * 将数据库行映射为 UserAccount 记录。
     * roles / permissions 以逗号分隔字符串存储，空字符串对应空集合。
     * preferences 存为 JSONB（测试 H2 用 VARCHAR），读出后解析为 Map。
     */
    private final RowMapper<UserAccount> rowMapper = (rs, rowNum) -> new UserAccount(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getBoolean("enabled"),
            parseSet(rs.getString("roles")),
            parseSet(rs.getString("permissions")),
            rs.getString("avatar_url"),
            rs.getString("email"),
            rs.getObject("github_id", Long.class),
            parseJson(rs.getString("preferences"))
    );

    public JdbcUserAccountRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<UserAccount> findById(Long id) {
        List<UserAccount> results = jdbc.query(
                "SELECT * FROM user_accounts WHERE id = ?", rowMapper, id);
        return results.stream().findFirst();
    }

    @Override
    public Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> results = jdbc.query(
                "SELECT * FROM user_accounts WHERE username = ?", rowMapper, username);
        return results.stream().findFirst();
    }

    @Override
    public List<UserAccount> findAll() {
        return jdbc.query("SELECT * FROM user_accounts ORDER BY id", rowMapper);
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
        String sql = "INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions, avatar_url, email, github_id) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, userAccount.username());
            ps.setString(2, userAccount.passwordHash());
            ps.setString(3, userAccount.displayName());
            ps.setBoolean(4, userAccount.enabled());
            ps.setString(5, joinSet(userAccount.roles()));
            ps.setString(6, joinSet(userAccount.permissions()));
            ps.setString(7, userAccount.avatarUrl());
            ps.setString(8, userAccount.email());
            // github_id 可为 null，用 setObject 处理
            ps.setObject(9, userAccount.githubId());
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
                userAccount.permissions(),
                userAccount.avatarUrl(),
                userAccount.email(),
                userAccount.githubId(),
                Map.of()
        );
    }

    @Override
    public UserAccount updateProfile(Long userId, String displayName, String avatarUrl, String email, Long githubId) {
        // 每次 GitHub 用户登录时刷新其展示名称、头像、邮箱等资料
        jdbc.update(
                "UPDATE user_accounts SET display_name = ?, avatar_url = ?, email = ?, github_id = ? WHERE id = ?",
                displayName, avatarUrl, email, githubId, userId);
        return findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
    }

    @Override
    public Map<String, Object> findPreferences(Long userId) {
        List<String> results = jdbc.query(
                "SELECT preferences FROM user_accounts WHERE id = ?",
                (rs, rn) -> rs.getString("preferences"),
                userId);
        if (results.isEmpty()) {
            throw new IllegalArgumentException("用户不存在: " + userId);
        }
        return parseJson(results.get(0));
    }

    @Override
    public Map<String, Object> updatePreferences(Long userId, Map<String, Object> merged) {
        // 接收已合并好的全量偏好，直接覆盖写入（合并逻辑在 service 层完成，兼容 H2 测试环境）
        String mergedJson = toJson(merged);
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                    "UPDATE user_accounts SET preferences = ? WHERE id = ?");
            // PostgreSQL 连接时用 PGobject 传 jsonb 类型；H2 等直接用 String
            String driverName = connection.getMetaData().getDriverName();
            if (driverName != null && driverName.toLowerCase().contains("postgresql")) {
                try {
                    var pgObjectClass = Class.forName("org.postgresql.util.PGobject");
                    var pgObject = pgObjectClass.getDeclaredConstructor().newInstance();
                    pgObjectClass.getMethod("setType", String.class).invoke(pgObject, "jsonb");
                    pgObjectClass.getMethod("setValue", String.class).invoke(pgObject, mergedJson);
                    ps.setObject(1, pgObject);
                } catch (Exception e) {
                    ps.setString(1, mergedJson);
                }
            } else {
                ps.setString(1, mergedJson);
            }
            ps.setLong(2, userId);
            return ps;
        });
        return findPreferences(userId);
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

    /**
     * 将 JSON 字符串解析为 Map，null 或解析失败时返回空 Map。
     */
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    /**
     * 将 Map 序列化为 JSON 字符串。
     */
    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }
}
