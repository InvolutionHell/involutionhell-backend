package com.involutionhell.backend.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.involutionhell.backend.chat.repository.ChatHistoryRepository;
import com.involutionhell.backend.chat.repository.ChatOwner;
import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 安全不变量回归测试。
 *
 * 本文件每条 @Test 都对应一条"如果断言失败，意味着某条安全防线被打穿"的场景。
 * 测试代码本身就是攻击脚本——它执行真实的越权调用，期望被服务端拒绝。
 *
 * 修改本文件中的任何 @Test 方法前，请同时更新 SECURITY.md 中对应的 INV-XXX 条目。
 * 想删除某条测试，必须在 PR 描述写明理由并 CC superadmin review。
 *
 * 编号约定：测试名前缀 {@code INV_001} 对应 SECURITY.md 里的 INV-001。
 */
class SecurityInvariantsTests extends AbstractWebIntegrationTest {

    /**
     * INV-002 用 mock 隔离：保护点是 controller 的鉴权判断，不依赖 SQL 实现。
     *
     * 为什么不用真 H2：JdbcChatHistoryRepository 用 PostgreSQL 专属的
     * INSERT ... ON CONFLICT (id) DO UPDATE SET 语法，H2 PostgreSQL MODE 不支持
     * 这条语法，连基础写入都跑不通。MockitoBean 替换 repository 之后，
     * controller 对 lookupOwner 返回值的不同分支就能干净地断言。
     */
    @MockitoBean
    private ChatHistoryRepository chatHistoryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * INV-001：admin 不能通过 PUT /users/{id}/authorization 给自己挂 superadmin。
     *
     * 攻击场景：admin 拥有 user:center:manage 权限可调本接口；
     * 若 service 层不拦截 superadmin，admin 即可整集替换 roles 自行提权。
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void admin不能通过PUT_users_authorization给自己加superadmin角色() throws Exception {
        String token = loginAsAdmin();

        // 攻击：admin 把自己的 roles 改成包含 superadmin
        mockMvc.perform(put("/users/1/authorization")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roles": ["superadmin", "admin", "user"],
                                  "permissions": ["user:profile:read", "user:center:read", "user:center:manage"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("不允许通过本接口授予角色")));

        // 二次验证：DB 里 admin 的 roles 没被污染
        mockMvc.perform(get("/users/1").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", Matchers.not(
                        Matchers.hasItem("superadmin"))));
    }

    /**
     * INV-001 孪生：admin 也不能给其他用户挂 superadmin。
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void admin不能通过PUT_users_authorization给他人加superadmin角色() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(put("/users/2/authorization")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roles": ["superadmin", "user"],
                                  "permissions": ["user:profile:read"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        // alice 的 roles 没被污染
        mockMvc.perform(get("/users/2").header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", Matchers.not(
                        Matchers.hasItem("superadmin"))));
    }

    /**
     * 反向 happy path：合法的角色更新仍然必须通过。
     * 防止"修了 superadmin 黑名单但把整个端点也搞坏了"这种过修。
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void admin可以通过PUT_users_authorization设置非superadmin的合法角色() throws Exception {
        String token = loginAsAdmin();

        mockMvc.perform(put("/users/2/authorization")
                        .header("satoken", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roles": ["editor", "reviewer"],
                                  "permissions": ["user:profile:read"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ============================================================
    // INV-002 · 不允许往他人的 chat 历史写消息
    // 见 SECURITY.md / ChatHistoryController#save
    // ============================================================

    /**
     * INV-002：匿名调用方拿到他人 chatId 后，不能往该 chat 写入新消息。
     *
     * 攻击场景：受害者登录创建 chat-uuid-A，ownerId 落库；
     * 攻击者通过 frontend log / share URL leak 拿到 chat-uuid-A，
     * 匿名 POST /api/chat/sessions/save 即可在 victim 历史里塞污染消息。
     *
     * 断言：controller 必须返 403 + saveTurn 一次都不能被调（防御深度——
     *       即便上层校验绕过，repository 也不应被触发）。
     */
    @Test
    void 匿名调用不能往他人chatId写消息() throws Exception {
        String victimChatId = UUID.randomUUID().toString();
        // mock 出"chat 已绑定 victim（uid=42）"的状态
        when(chatHistoryRepository.lookupOwner(victimChatId))
                .thenReturn(Optional.of(new ChatOwner(42L)));

        mockMvc.perform(post("/api/chat/sessions/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"%s","userMessage":"攻击者污染","assistantMessage":null}
                                """.formatted(victimChatId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        Matchers.containsString("不允许写入他人的 chat 历史")));

        // 防御深度：saveTurn 必须没被调用过
        verify(chatHistoryRepository, never()).saveTurn(any(), any(), any(), any());
    }

    /**
     * INV-002 孪生：登录的攻击者也不能写他人 chat。
     */
    @Test
    void 登录用户不能往他人chatId写消息() throws Exception {
        String victimChatId = UUID.randomUUID().toString();
        // mock 出"chat 已绑定 alice（uid=2）"
        when(chatHistoryRepository.lookupOwner(victimChatId))
                .thenReturn(Optional.of(new ChatOwner(2L)));

        // auditor（uid=3）登录后试图写 alice 的 chat
        String auditorToken = loginAsAuditor();
        mockMvc.perform(post("/api/chat/sessions/save")
                        .header("satoken", auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"%s","userMessage":"auditor 越权","assistantMessage":null}
                                """.formatted(victimChatId)))
                .andExpect(status().isForbidden());

        verify(chatHistoryRepository, never()).saveTurn(any(), any(), any(), any());
    }

    /**
     * 反向 happy path：owner 本人可以继续写自己的 chat。
     * 防止"修了归属校验但把整个端点也搞坏了"这种过修。
     */
    @Test
    void chat的owner可以继续写自己的chat历史() throws Exception {
        String chatId = UUID.randomUUID().toString();
        String aliceToken = loginAsAlice(); // alice id=2
        when(chatHistoryRepository.lookupOwner(chatId))
                .thenReturn(Optional.of(new ChatOwner(2L)));

        mockMvc.perform(post("/api/chat/sessions/save")
                        .header("satoken", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"%s","userMessage":"alice 自己的消息","assistantMessage":"AI 回复"}
                                """.formatted(chatId)))
                .andExpect(status().isOk());

        verify(chatHistoryRepository, times(1)).saveTurn(any(), any(), any(), any());
    }

    /**
     * 反向 happy path：匿名 chat 仍允许匿名继续写（保留"匿名 → 登录迁移"语义）。
     */
    @Test
    void 匿名chat允许匿名继续写() throws Exception {
        String chatId = UUID.randomUUID().toString();
        // 已存在的匿名 chat：lookupOwner 返回 ChatOwner(null)
        when(chatHistoryRepository.lookupOwner(chatId))
                .thenReturn(Optional.of(new ChatOwner(null)));

        mockMvc.perform(post("/api/chat/sessions/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"%s","userMessage":"匿名继续写","assistantMessage":null}
                                """.formatted(chatId)))
                .andExpect(status().isOk());

        verify(chatHistoryRepository, times(1)).saveTurn(any(), any(), any(), any());
    }

    /**
     * 反向 happy path：全新的 chatId（lookupOwner 返回 empty）允许任意调用方创建。
     */
    @Test
    void 全新chatId允许任意调用方首次写入() throws Exception {
        String chatId = UUID.randomUUID().toString();
        when(chatHistoryRepository.lookupOwner(chatId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/chat/sessions/save")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"%s","userMessage":"首次写入","assistantMessage":null}
                                """.formatted(chatId)))
                .andExpect(status().isOk());

        verify(chatHistoryRepository, times(1)).saveTurn(any(), any(), any(), any());
    }

    // ============================================================
    // INV-003 · 密码哈希必须用 bcrypt (legacy SHA-256 仅兼容路径)
    // 见 SECURITY.md / PasswordService
    // ============================================================

    /**
     * INV-003：seed 账号能用明文密码登录 = bcrypt seed 与 dual-mode matches 端到端联通。
     *
     * 防回归：如果有人误把 schema.sql / test-schema.sql 的 password_hash 回退到
     * 不可用的格式（错的 bcrypt / 错的明文），AbstractWebIntegrationTest 的
     * loginAndGetToken 会在 jsonPath 断言阶段抛 AssertionError，本测试随之红。
     *
     * password_hash 字段格式（必须 $2[aby]$ 起头、长度 60）由 PasswordServiceTests 覆盖。
     */
    @Test
    void seed账号能用明文密码登录验证bcrypt端到端() throws Exception {
        loginAsAdmin();
        loginAsAlice();
        loginAsAuditor();
    }

    // ============================================================
    // INV-004 · user_follows 表必须存在并能完成 follow/unfollow 闭环
    // 见 SECURITY.md / FollowService
    // ============================================================

    // ============================================================
    // INV-005 · docker-compose 不允许把 postgres 端口暴露公网，
    //         也不允许 password 字段保留 change_me fallback
    // 见 SECURITY.md / docker-compose.yml
    // ============================================================

    /**
     * INV-005a：docker-compose.yml 里 postgres 容器端口 5432 必须只绑 127.0.0.1。
     *
     * 攻击场景：把端口写成 "5432:5432" / "0.0.0.0:5432:5432" / "15432:5432"
     * 等任何"非 127.0.0.1: 开头"的形式，宿主机就把容器 5432 暴露给公网。
     *
     * 检测策略：扫描所有 ports 段 mapping 行，凡是 host port 段不以 127.0.0.1:
     * 起头且 container port 段为 5432 的，全判违规。覆盖：
     *   - 引号 / 无引号
     *   - host 改非 5432（如 15432）映射到容器 5432 仍暴露
     *   - 0.0.0.0 / ::（IPv6 全绑）等显式绑全网
     */
    @Test
    void docker_compose里postgres端口必须绑127_0_0_1() throws Exception {
        Path composeFile = locateComposeFile();
        String content = Files.readString(composeFile);

        // (?m) 行模式；匹配所有 "- <hostpart>:5432" 形式的 ports mapping。
        // 捕获组 1 是 hostpart（可能是 "127.0.0.1:5432"、"5432"、"0.0.0.0:5432" 等）。
        Pattern portMapping = Pattern.compile(
                "(?m)^\\s*-\\s*\"?([^\"\\s#]+):5432\"?\\s*(?:#.*)?$");
        java.util.regex.Matcher m = portMapping.matcher(content);
        java.util.List<String> violations = new java.util.ArrayList<>();
        while (m.find()) {
            String hostPart = m.group(1);
            // 合规形式：以 "127.0.0.1:" 起头（如 "127.0.0.1:5432"）。
            // 任何其他形式都不允许：纯端口号 "5432"、"0.0.0.0:..."、IPv6 "[::]:..."、
            // "15432" 这种 host port 改了但容器仍是 5432 也算（仍暴露公网）。
            if (!hostPart.startsWith("127.0.0.1:")) {
                violations.add(m.group(0).trim());
            }
        }
        Assertions.assertThat(violations)
                .as("docker-compose.yml postgres 容器 5432 端口必须只绑 127.0.0.1；"
                        + "检测到非 loopback 映射: %s", violations)
                .isEmpty();
    }

    /**
     * INV-005b：docker-compose.yml 里所有密码字段不允许保留 `change_me` 默认值，
     *           必须用 `${VAR:?...}` 强制要求 .env 显式提供。
     *
     * 防止部署者忘配 env 时容器以 change_me 弱密码起来对外服务。
     */
    @Test
    void docker_compose里不允许出现change_me弱密码默认值() throws Exception {
        Path composeFile = locateComposeFile();
        String content = Files.readString(composeFile);

        Assertions.assertThat(content)
                .as("docker-compose.yml 不允许再有 :-change_me} 形式的弱密码 fallback")
                .doesNotContain(":-change_me}");
    }

    /**
     * 解析 docker-compose.yml 的位置——maven test 工作目录就是 backend/ 仓库根。
     */
    private Path locateComposeFile() {
        Path p = Paths.get("docker-compose.yml");
        Assertions.assertThat(Files.exists(p))
                .as("找不到 docker-compose.yml；测试工作目录应是 backend/ 根")
                .isTrue();
        return p;
    }

    /**
     * INV-004：user_follows 表必须存在并能 INSERT/SELECT/DELETE。
     *
     * 之前 user_follows 表在 schema.sql / init.sql 都没建——任何首次部署
     * 调 /api/user-center/follows/... 必 500，因为 relation does not exist。
     *
     * 不走 FollowService.follow() 端到端是因为 FollowService 用 PG 专属
     * `ON CONFLICT (follower_id, followee_id) DO NOTHING`，H2 PostgreSQL MODE
     * 当前版本不识别该 ON CONFLICT 形式。直接打 JdbcTemplate 验证表/索引存在
     * + 字段顺序与代码期望对齐——schema drift 的根因正是 DDL 缺失，本测试
     * 在 schema.sql / test-schema.sql 漏掉表时立即红。
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void user_follows表存在且字段可读写() {
        // INSERT：表存在 + 三个字段名拼写正确
        jdbcTemplate.update(
                "INSERT INTO user_follows (follower_id, followee_id) VALUES (?, ?)",
                2L, 1L);

        // SELECT：FollowService 实际查询的字段都拼得通
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_follows WHERE follower_id = ? AND followee_id = ?",
                Long.class, 2L, 1L);
        Assertions.assertThat(count).isEqualTo(1L);

        // DELETE：unfollow 路径
        int deleted = jdbcTemplate.update(
                "DELETE FROM user_follows WHERE follower_id = ? AND followee_id = ?",
                2L, 1L);
        Assertions.assertThat(deleted).isEqualTo(1);
    }
}
