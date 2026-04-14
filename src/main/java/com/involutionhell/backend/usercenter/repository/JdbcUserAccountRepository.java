package com.involutionhell.backend.usercenter.repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.involutionhell.backend.usercenter.model.UserAccount;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(JdbcUserAccountRepository.class);

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
        // 把 preferences 一并写入 INSERT，避免创建用户时携带的初始偏好被丢弃
        String sql = "INSERT INTO user_accounts (username, password_hash, display_name, enabled, roles, permissions, avatar_url, email, github_id, preferences) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        String prefsJson = toJson(userAccount.preferences());

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
            // jsonb 用 Types.OTHER 让 PostgreSQL 驱动自行识别；H2 会当作字符串处理
            ps.setObject(10, prefsJson, Types.OTHER);
            return ps;
        }, keyHolder);

        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入用户失败，无法获取生成的 ID");
        }

        // 插入后回读整行，确保返回值与数据库一致，避免遗漏新列或字段漂移
        return findById(key.longValue())
                .orElseThrow(() -> new IllegalStateException("插入用户后无法读取回数据: id=" + key.longValue()));
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
    public Map<String, Object> patchPreferences(Long userId, Map<String, Object> patch) {
        // 直接在 DB 端做原子 merge，避免 Java 侧 read-merge-write 的并发 lost update；
        // 同时用 setObject + Types.OTHER，避开反射 PGobject 在 GraalVM native image 下
        // reflection hints 未注册导致的启动失败。
        //
        // PostgreSQL：UPDATE ... SET preferences = preferences || ?::jsonb
        //   使用 jsonb 原生 `||` 操作符做顶层 key 合并，单条语句原子完成
        // H2（测试环境）：UPDATE ... SET preferences = ? （全量覆盖）
        //   测试环境不追求并发正确性，由 service 层先 read-merge-write 保证合并语义
        String patchJson = toJson(patch);
        boolean isPostgres = isPostgres();

        if (isPostgres) {
            int updated = jdbc.update(connection -> {
                var ps = connection.prepareStatement(
                        "UPDATE user_accounts SET preferences = preferences || ?::jsonb WHERE id = ?");
                ps.setObject(1, patchJson, Types.OTHER);
                ps.setLong(2, userId);
                return ps;
            });
            if (updated == 0) {
                throw new IllegalArgumentException("用户不存在: " + userId);
            }
        } else {
            // H2 路径：先读后合并再整体写入（测试环境无并发压力）
            Map<String, Object> existing = findPreferences(userId);
            Map<String, Object> merged = new HashMap<>(existing);
            merged.putAll(patch);
            String mergedJson = toJson(merged);
            int updated = jdbc.update(
                    "UPDATE user_accounts SET preferences = ? WHERE id = ?",
                    mergedJson, userId);
            if (updated == 0) {
                throw new IllegalArgumentException("用户不存在: " + userId);
            }
        }

        return findPreferences(userId);
    }

    /** 判断当前数据源是否为 PostgreSQL（通过驱动名识别）。 */
    private boolean isPostgres() {
        try {
            return Boolean.TRUE.equals(jdbc.execute((java.sql.Connection c) -> {
                String name = c.getMetaData().getDriverName();
                return name != null && name.toLowerCase().contains("postgresql");
            }));
        } catch (Exception e) {
            log.warn("检测数据源驱动失败，按非 PostgreSQL 兜底: {}", e.getMessage());
            return false;
        }
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
     * 将 JSON 字符串解析为 Map。
     * null / 空串 / "{}" 视为"未设置"返回空 Map；解析失败（数据库里脏数据）则抛出异常，
     * 避免静默吞错，让调用方感知并由全局异常处理器返回 500。
     */
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.error("解析 preferences JSON 失败，数据可能已损坏: {}", json, e);
            throw new IllegalStateException("解析 preferences 失败", e);
        }
    }

    /**
     * 将 Map 序列化为 JSON 字符串。
     * 失败时抛出异常而不是返回 "{}"，避免把有问题的偏好当成空偏好静默覆盖掉原有数据。
     */
    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.error("序列化 preferences 失败: {}", map, e);
            throw new IllegalStateException("序列化 preferences 失败", e);
        }
    }
}
