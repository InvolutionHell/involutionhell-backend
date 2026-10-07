# usercenter — 登录 / 身份 / 账号

这个包管三件事:**认证**(你是谁)、**身份绑定**(哪些第三方账号属于同一个人)、
**账号**(资料/角色/关注)。接入新的登录方式(Google、Microsoft…)之前先读完这篇。

> 安全不变量 INV-001..INV-008 在 `backend/SECURITY.md`,每条都有回归测试。
> 改 auth/角色/密码/关注逻辑前必须先读那份,本文不重复。

---

## 1. 接入一个新的登录 provider

**只需要新增一个类**,不改任何已有文件:

```java
@Component
public class GoogleAuthProvider implements AuthProvider {
    // 构造器用 @Value 注入 client-id / secret / redirect-uri

    @Override public String key() { return "google"; }          // 小写，与 URL 段、DB 列一致

    @Override public AuthRequest newRequest() {
        AuthProviders.requireConfigured(key(), clientId, clientSecret);
        return new AuthGoogleRequest(AuthConfig.builder()...build());   // JustAuth 内置就直接用
    }

    @Override public String redirectUri() { return redirectUri; }

    @Override public boolean isEmailVerified(AuthUser user) {
        // ⚠️ 安全判据，见下面第 2 节。拿不准就 return false
        return user.getRawUserInfo().getBooleanValue("email_verified");
    }
}
```

`AuthProviderRegistry` 会自动收集所有 `AuthProvider` bean,**登录、绑定、邮箱信任判定
三条路径立刻全部生效**。

### 为什么是这个形状

历史上 provider 名散落在三个 `switch` 里(`authRequestFor` / `isProviderEmailVerified` /
`redirectUriOf`),接一个新 provider 要改 6 处,漏改一处就得到"能跳转但邮箱不被信任"
这种半死状态——排查起来极其痛苦,因为登录看起来是成功的,只是**悄悄多建了一个账号**。
接口把这些收敛成一个类。

### 剩下必须手动改的地方(共 3 处,无法再收敛)

| 位置 | 改什么 | 不改会怎样 |
|---|---|---|
| `application.properties` | `justauth.type.<key>.client-id/secret/redirect-uri` | provider 被判为"未配置",跳 `error=oauth_provider` |
| `frontend/messages/{zh,en}.json` | `login.<key>` 按钮文案 | 按钮显示原始 key |
| `frontend/app/components/SignInButton.tsx` | `provider` 联合类型加一项 | typecheck 失败 |

前端**不需要**再维护"有哪些 provider"的列表:`GET /api/user-center/identities/providers`
返回后端已注册的全部 key,设置页据此渲染"连接"按钮。

### 上线前自查

- [ ] provider 的 OAuth App 回调 URL 与 `redirect-uri` 完全一致(含 scheme 和结尾斜杠)
- [ ] `isEmailVerified` 的判据查过该 provider 官方文档,不是猜的
- [ ] `next.config.mjs` 的 rewrite 覆盖 `/oauth/*` 与 `/api/auth/callback/*`(已覆盖,新增 provider 无需改)
- [ ] 本地跑通:登录建号 → 登出 → 再登录命中同一账号 → 设置页能看到该身份

---

## 2. 邮箱信任是安全判据,不是展示字段

`isEmailVerified` 决定**是否允许把这个第三方身份自动挂靠到一个已有账号上**。

- 返回 `true` → provider 断言"这个邮箱确实属于本人",匹配到唯一已有账号就直接挂靠,
  用户无感,不会被分叉出第二个账号。
- 返回 `false` → 一律建新账号。

**返回 true 的门槛是"provider 保证用户控制该邮箱"**。如果一个 provider 允许用户填任意
邮箱而不验证,那么返回 true 就等于把账号接管漏洞焊死进登录流程:攻击者注册一个第三方号、
邮箱填成受害者的,登录即接管。`default` 分支永远是 `false`,新 provider 拿不准也是 `false`
——代价只是多一个账号,而不是账号被偷。

已有判据:

| provider | 判据 | 依据 |
|---|---|---|
| github | 恒 `true` | JustAuth 取的是 primary email,GitHub 强制 primary 已验证 |
| discord | `/users/@me` 的 `verified` 布尔 | Discord 官方字段 |

---

## 3. 两条 OAuth 流程

两条流程**共用同一个回调端点** `/api/auth/callback/{provider}`,走哪条由**服务端**的
绑定意图决定,不由任何请求参数决定。

```
登录  GET /oauth/render/{provider}   →  provider 授权页  →  回调  →  建号或按已验证邮箱挂靠  →  发 token
绑定  GET /oauth/bind/{provider}     →  provider 授权页  →  回调  →  挂到当前账号
      （需已登录）                                                    不建号 / 不换会话 / 不发新 token
```

### 绑定流程为什么不能把 userId 放进 state(INV-007)

`state` 是客户端可见、可伪造的。把"绑定到哪个账号"写进 state,攻击者就能构造一个
"绑定到我的账号"的 state 诱导受害者走完授权,把受害者的第三方身份绑到攻击者账号上。

正确做法(当前实现):

1. `/oauth/bind/{provider}` 上有 `@SaCheckLogin`,userId 取自**服务端已校验的会话**
2. 以随机 `state` 为 key,把 userId 存进服务端内存(`bindIntents`,5 分钟 TTL,一次性消费)
3. 回调时用 state 取回意图,并**二次核对当前会话仍是同一个人**(中途登出/换号则拒绝)
4. `state` 同时走 `ih_oauth_state` httpOnly cookie 双提交校验(防登录 CSRF)

`state` 在这里只是**不可猜测的查找键**,不承载任何可信信息。

### 绑定冲突

对应 `user_identities` 的两条唯一约束,都返回可辨识的 code 而非 500:

| code | 含义 | 用户该怎么办 |
|---|---|---|
| `bind_taken` | 该第三方账号已绑到**别的**账号 | 先去那个账号解绑 |
| `bind_duplicate` | 本账号已绑过同类 provider | 先解绑再绑新的 |
| `bind_already_yours` | 已经绑在你自己账号上了 | 无需操作 |
| `bind_session` | 从发起到回调之间会话变了 | 重新发起 |

> **为什么绑定入口必须早于"对所有人开放新 provider"**
> `UNIQUE (provider, provider_user_id)` 意味着一个第三方身份只能绑一个账号。
> 先开放 → 用户被分叉出新账号 → 新账号**占住**了那个身份 → 本尊再想补绑就撞约束,
> 从"插一行"变成"跨账号迁移 posts/chat/follows 的数据合并"。
> 顺序错了,成本差一个数量级。

---

## 4. 账号 vs 身份

```
user_accounts   ← 人。opaque BigInt id，不受任何 provider 控制，永不变
   ▲  ▲  ▲
   │  │  └── user_identities(google,  <sub>)
   │  └───── user_identities(discord, <snowflake>)
   └──────── user_identities(github,  <numeric id>)
```

- **主键是我们自己的 surrogate id**,不是邮箱、也不是任何 provider 的 id。
  邮箱只是"自动挂靠的锚",会变、且不是每个 provider 都给。
- `user_accounts.github_id` 是**双写期遗留**(贡献归属与 `/u/{githubId}` 还依赖它)。
  绑定 github 时补写、解绑时清空,两侧必须对称——否则 `schema.sql` 的启动回填会按
  残留列值把身份静默复活。M4 会移除该列。
- 解绑不能移除**最后一种**登录方式(否则用户永久锁死),前后端都要拦。

---

## 5. 灰度闸(Discord 当前状态)

`auth.discord.allowlist` 非空即启用:只放行名单内的 Discord id **以及已有账号的回访登录**。
闸保护的是**建新号**,不是把老用户锁在自己账号外面;绑定流程同理不经过闸(绑定不建号)。

清空该变量 = GA。**GA 前提是新用户建号路径已经安全**(见 SECURITY.md INV-008)。

---

## 6. 目录速查

```
controller/  AuthController(密码登录/登出/me) · OAuthController(登录+绑定) · IdentityController(查看/解绑/可绑列表)
             UserCenterController · UserPreferencesController
oauth/       AuthProvider(接口) · AuthProviderRegistry(单一真相源) · GithubAuthProvider · DiscordAuthProvider
             DiscordAuthSource / AuthDiscordRequest(JustAuth 没内置 Discord，自己实现)
service/     AuthService(登录/自动挂靠) · UserIdentityService(绑定/解绑) · PasswordService(INV-003)
             RegistrationService(注册 OTP 引擎，尚未接线) · UserCenterService(账号)
repository/  Jdbc*(裸 JDBC，无 ORM；表结构以 schema.sql 为准)
follows/     关注关系（社交，与认证无关）
```
