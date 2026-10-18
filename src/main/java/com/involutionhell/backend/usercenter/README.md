# usercenter — 登录 / 身份 / 账号

这个包管三件事:**认证**(你是谁)、**身份绑定**(哪些第三方账号属于同一个人)、
**账号**(资料/角色/关注)。接入新的登录方式(Google、Microsoft…)之前先读完这篇。

> 安全不变量见 `backend/SECURITY.md`(编号持续新增,以那份为准),每条都有回归测试。
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
- [ ] 本地跑通:登录 → 验证邮箱建号 → 登出 → 再登录命中同一账号 → 设置页能看到该身份
      (没配 Resend 就开 `REGISTRATION_OTP_DEV_CONSOLE=true`,验证码打在后端日志里)

---

## 2. 邮箱信任是安全判据,不是展示字段

`isEmailVerified` 决定**是否允许把这个第三方身份自动挂靠到一个已有账号上**。

- 返回 `true` → provider 断言"这个邮箱确实属于本人",匹配到唯一已有账号就直接挂靠,
  用户无感,不会被分叉出第二个账号。
- 返回 `false` → 不挂靠,进 OTP 注册(用户自己验证一个邮箱后建新号)。

**返回 true 的门槛是"provider 保证用户控制该邮箱"**。如果一个 provider 允许用户填任意
邮箱而不验证,那么返回 true 就等于把账号接管漏洞焊死进登录流程:攻击者注册一个第三方号、
邮箱填成受害者的,登录即接管。新 provider 拿不准就是 `false`——代价只是用户多验证一次邮箱,
而不是账号被偷。

同一个判据还决定**这个邮箱能不能写进 `user_accounts.email`**(它就是挂靠的锚点):未验证的
provider 邮箱只拿来预填验证页,永远不落库(SECURITY.md INV-012)。

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
登录  GET  /oauth/render/{provider}  →  provider 授权页  →  回调  →  身份行 → 用户名(仅无身份行的老账号) → 已验证邮箱挂靠
                                                                     ├ 命中 → 302 /#token=<satoken>&n=<render 时的 ?n=>(INV-007)
                                                                     └ 新用户 → 302 /login/verify + ih_reg_pending cookie → OTP 注册(见下)
绑定  POST /oauth/bind/{provider}    →  返回 {url},前端跳转  →  provider 授权页  →  回调  →  挂到当前账号
      （需已登录）                                                                         不建号 / 不换会话 / 不发新 token
```

回调里 `loginByProvider` 抛异常时:账号禁用 → `/login?error=account_disabled`,其它 → `/login?error=oauth_failed`。

### 新用户注册(OTP)

`loginByProvider` **从不建号**。建号只有 `AuthService#completeRegistration` 一个入口,只在邮箱
验证码通过之后(SECURITY.md INV-012)。三个端点都公开,`ih_reg_pending` httpOnly cookie 是唯一凭据:

| 端点 | 成功 | 失败 |
|---|---|---|
| `GET /auth/register/pending` | 200 预填资料(provider、展示名、头像、provider 给的邮箱) | 410 会话没了 |
| `POST /auth/register/send-otp {email}` | 200 | 400 邮箱格式 / 410 / 429 限流 / 503 发信失败可重试 |
| `POST /auth/register/verify {email, code}` | 200,与 `POST /auth/login` 同形的 token | 400 码不对(会话还在) / 409 邮箱已属于已有账号 / 410 |

- pendingId **只进 cookie、不进 URL**:URL 能被转发,受害者在攻击者的注册会话里验证自己的邮箱,
  攻击者的第三方身份就拿到了受害者的已验证邮箱(预注册劫持)。
- 409 不合并:让用户用原来的方式登录,再到设置页绑定这个 provider。
- 限流按收件邮箱(5 封/天)与第三方身份(10 封/天),叠加会话内 5 封、60s 冷却;累计 5 次错码作废会话。
  不按 IP:后端看不到可信的客户端 IP。
- 用户名沿用 `{provider}_{id}`;被一个已解绑这个身份的老账号占着时加 6 位随机后缀。

### 绑定流程为什么不能把 userId 放进 state(INV-007)

`state` 是客户端可见、可伪造的。把"绑定到哪个账号"写进 state,攻击者就能构造一个
"绑定到我的账号"的 state 诱导受害者走完授权,把受害者的第三方身份绑到攻击者账号上。

正确做法(当前实现):

1. `POST /oauth/bind/{provider}` 上有 `@SaCheckLogin`,userId 取自**服务端已校验的会话**。
   必须是 POST:GET 能被跨站顶级导航触发,带着被种进浏览器的别人的 satoken cookie 就能发起绑定
2. 以随机 `state` 为 key,把 userId 存进服务端内存(`bindIntents`,5 分钟 TTL,一次性消费)
3. 回调时用 state 取回意图,并**二次核对当前会话仍是同一个人**(中途登出/换号则拒绝)
4. `state` 同时走 `ih_oauth_state` httpOnly cookie 双提交校验(防登录 CSRF)
5. 发起绑定还种 `ih_oauth_bind` 标记(30 分钟,长于 5 分钟的 state cookie);回调见标记却取不到
   意图(过期/重放/重启)就弹回 `/settings?bind_error=...`,**绝不落进登录分支**——否则会按该
   第三方身份进注册或登进别的账号

`state` 在这里只是**不可猜测的查找键**,不承载任何可信信息。

### 绑定冲突

对应 `user_identities` 的两条唯一约束,都返回可辨识的 code 而非 500:

| code | 含义 | 用户该怎么办 |
|---|---|---|
| `bind_taken` | 该第三方账号已绑到**别的**账号 | 那个账号多半只有这一种登录方式、解绑不了,找管理员合并 |
| `bind_duplicate` | 本账号已绑过同类 provider | 先解绑再绑新的 |
| `bind_already_yours` | 已经绑在你自己账号上了 | 无需操作 |
| `bind_session` | 从发起到回调之间会话变了,或绑定意图已失效(过期/重放) | 重新发起 |

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
- **解绑即撤销登录**:`{provider}_{id}` 用户名回退只认一行身份都没有的老账号。否则解绑/换绑掉的
  身份按用户名还能登回来,并被静默补回身份行,设置页看不到、撤不掉。
- 资料刷新:用账号的原始 provider(用户名前缀)登录时刷新展示名/头像,别家登录只补空;
  email、github_id 永远只补空。

---

## 5. 灰度闸(Discord 当前状态)

`auth.discord.allowlist` 非空即启用:只放行名单内的 Discord id **以及已有账号的回访登录**。
闸保护的是**建新号**,不是把老用户锁在自己账号外面;绑定流程同理不经过闸(绑定不建号)。

新用户建号已走 OTP 注册,2026-10-18 清空该变量 GA。闸的代码保留一个版本当回滚开关:要重新关上
Discord 注册,设回 `AUTH_DISCORD_ALLOWLIST=<id>` 重建容器即可(见 SECURITY.md INV-008)。

---

## 6. 目录速查

```
controller/  AuthController(密码登录/登出/me/注册 OTP) · OAuthController(登录+绑定) · IdentityController(查看/解绑/可绑列表)
             UserCenterController · UserPreferencesController
oauth/       AuthProvider(接口) · AuthProviderRegistry(单一真相源) · GithubAuthProvider · DiscordAuthProvider
             DiscordAuthSource / AuthDiscordRequest(JustAuth 没内置 Discord，自己实现)
service/     AuthService(登录/自动挂靠/建号) · UserIdentityService(绑定/解绑) · PasswordService(INV-003)
             RegistrationService(注册 OTP 引擎) · UserCenterService(账号)
repository/  Jdbc*(裸 JDBC，无 ORM；表结构以 schema.sql 为准)
follows/     关注关系（社交，与认证无关）
```
