# 后端安全不变量（Security Invariants）

本文档登记后端代码中**不可变更的安全保护点**。
每一条不变量都对应 `SecurityInvariantsTests` 中的回归测试，CI 自动跑。

## 维护规则

修改本文件涉及的代码时，**必须同时更新对应测试**。
删除任何一条不变量需在 PR 描述写明理由并 CC superadmin review。

每条不变量包含四个字段：
- **保护点**：被这条规则保护的代码位置（file path + 类/方法名）
- **测试**：验证这条规则的测试方法名（直接 `./mvnw test -Dtest=SecurityInvariantsTests` 跑得到）
- **为什么**：规则存在的理由——攻击场景 / 历史 / 业务约束
- **历史**：规则诞生时间与背景（commit、CR 报告、incident）

## 编号约定

`INV-XXX` 三位流水号，永不复用。删除条目时保留编号 + 标 `(retired)`，
新条目用下一个未占用号。这样 PR diff / 历史回溯永远稳定。

---

## INV-001 · superadmin 角色不能通过 API 授予

- **保护点**：
  - `usercenter/service/UserCenterService#updateAuthorization`
  - `events/controller/AdminUserController#setAdminRole`
- **测试**：
  - `SecurityInvariantsTests#admin不能通过PUT_users_authorization给自己加superadmin角色`
  - `SecurityInvariantsTests#admin不能通过PUT_users_authorization给他人加superadmin角色`
  - `SecurityInvariantsTests#admin可以通过PUT_users_authorization设置非superadmin的合法角色`（反向 happy path）
- **为什么**：superadmin 是 admin 的上位角色（能管理其他 admin 的角色绑定）。
  如果 admin 能通过任何 API 拿到 superadmin，整套 RBAC 边界失效。
  唯一合法的 superadmin 授予路径：DB 直接 `UPDATE user_accounts SET roles = ...`。
- **历史**：2026-05-07 三方 CR 的 attack chain A 起点（详见内部报告）。

---

## INV-002 · 不允许往他人的 chat 历史写消息

- **保护点**：`chat/controller/ChatHistoryController#save`
- **测试**：
  - `SecurityInvariantsTests#匿名调用不能往他人chatId写消息`
  - `SecurityInvariantsTests#登录用户不能往他人chatId写消息`
  - `SecurityInvariantsTests#chat的owner可以继续写自己的chat历史`（反向）
  - `SecurityInvariantsTests#匿名chat允许匿名继续写`（反向，保留匿名 → 登录迁移语义）
- **为什么**：chat 历史既是用户隐私也是 AI 训练 / 行为审计的输入。
  如果攻击者拿到他人 chatId（前端 log / share URL leak），匿名 POST 即可往
  victim 历史里塞污染消息。COALESCE 语义不会改 ownerId，但 INSERT INTO
  "Message" 已经发生——这是数据完整性 + 内容污染。
  匿名 chat 允许继续匿名写，保留"匿名 → 登录迁移"业务语义；
  一旦 chat 已绑定 ownerId，则只允许 owner 本人写。
- **历史**：2026-05-07 三方 CR 的 attack chain B 起点。

---

## INV-003 · 密码哈希必须用 bcrypt（legacy SHA-256 仅兼容路径）

- **保护点**：
  - `usercenter/service/PasswordService#hash`（始终输出 bcrypt）
  - `usercenter/service/PasswordService#matches`（dual-mode：bcrypt + legacy SHA-256）
  - `usercenter/service/AuthService#login`（lazy upgrade：登录成功后把 legacy hash 就地升级）
  - `docker/init-db/init.sql`（seed bcrypt 哈希；schema.sql 不再 seed，见 INV-011）
  - `src/test/resources/test-schema.sql`（seed bcrypt 哈希）
- **测试**：
  - `PasswordServiceTests#hashGeneratesBcryptOutput`
  - `PasswordServiceTests#hashSamePasswordTwiceProducesDifferentOutput`（salt 随机性证据）
  - `PasswordServiceTests#matchesAcceptsLegacySha256Hash`（向后兼容）
  - `PasswordServiceTests#isLegacyHashIdentifiesPlainSha256`
  - `SecurityInvariantsTests#seed账号能用明文密码登录验证bcrypt端到端`
- **为什么**：原实现用单轮 SHA-256，无 salt 无 cost factor。
  DB 一旦泄露（备份、容器逃逸、pgAdmin 误导出），所有口令账号瞬间失守——
  GPU 一秒可跑数十亿哈希，rainbow table 已收录绝大部分常见明文。
  迁移策略：新写入一律 bcrypt；老 SHA-256 hash 通过 dual-mode matches 让用户能登录，
  登录成功时由 AuthService 就地升级。所有 seed 同步重写为 bcrypt 形式。
- **历史**：2026-05-07 三方 CR 的 attack chain B 加固项。

---

## INV-004 · user_follows 表必须随 schema 一并建立

- **保护点**：
  - `src/main/resources/schema.sql`（user_follows DDL + idx_user_follows_followee 索引）
  - `docker/init-db/init.sql`（同步 DDL）
  - `src/test/resources/test-schema.sql`（测试同步）
  - `usercenter/follows/FollowService`（SQL 调用方）
- **测试**：`SecurityInvariantsTests#user_follows表存在且字段可读写`
  （不直接调 FollowService.follow() 端到端，因 FollowService 用 PG 专属
  `ON CONFLICT (cols) DO NOTHING`，H2 PostgreSQL MODE 当前版本不识别——
  改打 JdbcTemplate 直读直写表，验证 schema drift 这一根因）
- **为什么**：FollowService 引用的 `user_follows(follower_id, followee_id, created_at)`
  之前在 schema.sql 与 init.sql 中都未定义，全新部署或新 Neon 数据库一访问
  `/api/user-center/follows/...` 就 `relation does not exist` 报 500。
  这是 issue #25 之外的额外 schema drift。CI 测试存在表 + 字段可读写作为
  长期回归探测器。
- **历史**：2026-05-07 三方 CR 发现的 schema drift（与 issue #25 类同）。

---

## INV-005 · docker-compose 不允许暴露公网 PG 端口或保留弱密码默认值

- **保护点**：`docker-compose.yml`（postgres 服务 ports 段、所有密码字段）
- **测试**：
  - `SecurityInvariantsTests#docker_compose里postgres端口必须绑127_0_0_1`
  - `SecurityInvariantsTests#docker_compose里不允许出现change_me弱密码默认值`
- **为什么**：PostgreSQL 主端口暴露公网 = 攻击面 +1（弱密码扫库 / 0day / TLS 退化）。
  pgAdmin 走 docker internal network 即可访问，不需要 host port mapping。
  `change_me` fallback 让部署者忘配 env 时仍能起服务，但起来的就是弱密码后台
  ——必须用 `${VAR:?...}` 形式强制部署期校验。
- **历史**：2026-05-07 三方 CR 加固项（端口部分历史已修，本次加测试 + 收紧密码默认值）。

## INV-006 · 付费 LLM 端点必须每用户限流

- **保护点**：`OpenAiStreamController#streamResponses`（`/openai/responses/stream`）
  与 `OpenAiStreamRateLimiter`
- **测试**：
  - `OpenAiStreamRateLimiterTests#underLimitPassesAndOverLimitGets429`
  - `OpenAiStreamRateLimiterTests#usersAreIsolated`
  - `OpenAiStreamRateLimiterTests#windowExpiryResetsTheCounter`
- **为什么**：该端点烧付费 LLM 额度。`@SaCheckLogin` 只挡未登录；登录用户可
  绕过 Next.js 层的 Upstash 限流直接 curl 后端（Caddy 裸透传不过滤路径），
  无限流时单用户即可刷爆账单（#297 估算 ~$5/小时）。限流必须落在 Java 层
  本身，不能只依赖前端网关。上限经 `openai.stream.requests-per-minute`
  配置（默认 10/分钟/用户），调大需说明场景。
- **历史**：2026-04-16 由 #297 报告；2026-07-18 加限流 + 本不变量。

## INV-007 · OAuth callback 不得信任 state 中的用户身份

- **保护点**：`OAuthController#login`（`/api/auth/callback/{provider}`）的 state
  双提交校验——URL 里的 `state` 必须等于本次 `renderAuth` 种下的 `ih_oauth_state`
  httpOnly cookie，缺失/不匹配即在换 token 前拒绝。
  登录成功的 `#token=` 必须带回发起登录那个 tab 的 nonce：前端（SignInButton）生成随机 `n`
  存进 sessionStorage，`GET /oauth/render/{provider}?n=` 把它种进 `ih_login_nonce` httpOnly cookie
  （`^[A-Za-z0-9_-]{16,64}$` 才种，Max-Age 同 state cookie 300s），回调成功跳
  `/#token=<T>&n=<n>`；回调的每个出口都清掉这个 cookie。前端（`lib/use-auth.tsx`）只收 `n` 与本 tab
  存的值相等的 token。
- **测试**：
  - `OAuthControllerIntegrationTests#callbackWithoutStateCookieIsRejectedBeforeTokenExchange`
  - `OAuthControllerIntegrationTests#callbackWithMatchingStateCookieProceedsPastStateCheck`（反向）
  - `OAuthControllerIntegrationTests#renderSetsStateCookie`（前置条件）
  - `OAuthControllerIntegrationTests#renderWithValidNonceSetsLoginNonceCookie`
  - `OAuthControllerIntegrationTests#renderWithInvalidOrMissingNonceSetsNone`
  - `OAuthControllerIntegrationTests#loginSuccessEchoesNonceFromCookieAndClearsIt`
  - `OAuthControllerIntegrationTests#loginSuccessWithoutNonceCookieRedirectsWithPlainToken`（老前端兼容）
  - `OAuthControllerIntegrationTests#nonceCookieIsClearedOnErrorAndRegistrationRedirects`
  - `OAuthControllerIntegrationTests#bindEntryRequiresLogin`（绑定入口鉴权）
  - `OAuthControllerIntegrationTests#bindEntryRequiresLoginEvenForUnknownProvider`
  - `OAuthControllerIntegrationTests#bindStartIsNotAcceptedViaGet`
  - `OAuthControllerIntegrationTests#bindStartReturnsAuthorizeUrlAndSetsStateAndMarkerCookies`（反向）
  - `OAuthControllerIntegrationTests#bindStartWithUnknownProviderIsJson4xxAndSetsNoCookies`
  - `OAuthControllerIntegrationTests#bindCallbackInSameSessionAttachesIdentityWithoutCreatingAccount`（反向）
  - `OAuthControllerIntegrationTests#bindCallbackAfterLogoutIsRejected`
  - `OAuthControllerIntegrationTests#bindCallbackUnderAnotherUsersSessionIsRejected`
  - `OAuthControllerIntegrationTests#replayedBindCallbackIsRejectedInsteadOfFallingIntoLogin`
  - `OAuthControllerIntegrationTests#bindThatOutlivesStateCookieReturnsToSettingsNotLogin`
- **为什么**：callback 是 provider 发起的顶级 GET，不带 Authorization header；若
  直接信任 URL `state`（尤其未来绑定流程把 loginId 塞进 state），攻击者可发起
  流程拿到合法 state 诱导受害者授权，把受害者的第三方身份绑/登进攻击者预期的
  账号（登录 CSRF / 绑定劫持）。防线：state 必须回证到"发起本次流程的同一浏览器"
  ——即 render 时种下、callback 时比对的 cookie，攻击者无法向受害者浏览器种此
  cookie。

  **绑定流程（M2b，已实现）同理**：`POST /oauth/bind/{provider}` 带 `@SaCheckLogin`，
  返回 provider 授权地址（`{url}`）由前端跳转；**不接受 GET**——GET 能被跨站顶级导航触发，
  配合被种进受害者浏览器的攻击者 satoken cookie（SameSite=Lax 照样随 GET 导航发送），
  就能把受害者的第三方身份绑进攻击者账号。userId 取自服务端已校验的会话；以随机 state 为 key 把它存进服务端内存
  （`OAuthController#bindIntents`，5 分钟 TTL、一次性消费），回调时取回并**二次核对
  当前会话仍是同一个人**（中途登出/换号即拒绝）。state 在此仅作不可猜测的查找键，
  不承载任何可信信息——把 userId 写进 state 就等于把绑定劫持焊死进流程。
  意图丢失（过期/重放/进程重启）时回调**不得**落进登录分支——否则会按该第三方身份
  建号或登进别的账号。发起绑定时同时种 `ih_oauth_bind` 标记 cookie（发起登录时清掉），
  回调见标记而无意图即在换 token 前弹回 `/settings?bind_error=bind_session`。
  标记寿命 30 分钟，**必须长于** 5 分钟的 state cookie：两者同时过期的话，在 provider 页
  停太久的绑定回来什么都不剩，会被送去 `/login?error=oauth_state`，用户再点登录就分叉出新账号；
  标记还在则回到 `/settings?bind_error=oauth_state`。
  **nonce 回显**补的是 state 管不到的另一半：state 只证明"回调属于发起它的浏览器"，挡不住攻击者用
  自己的账号走完登录、再把受害者的 tab 导航到 `https://involutionhell.com/#token=<攻击者 token>`
  （`window.open` 拿到的句柄、provider 授权页都不切断 opener）。受害者就登进了攻击者的账号，之后的
  AI 对话、投稿、甚至设置页里的"连接"都落到攻击者名下。攻击者拿不到受害者 tab 里的 nonce，
  带不出能对上的 `n`。nonce 放 cookie 而不是 state：state 进 provider 的 URL，cookie 只在本浏览器。
  cookie 缺失时退回不带 `n` 的 `#token=`，只为让旧前端在后端先上线期间照常登录；新前端拒收它。
- **历史**：2026-07-19 随多 provider 身份体系 M1 引入（RFC #42 / ADR-001）。
  编号说明：INV-006 已被"付费 LLM 端点限流"占用，按流水规则用 INV-007。
  2026-10-18 #57 review：绑定发起由 cookie 鉴权的 GET 改为 POST（review 复现了"种 token +
  顶级导航"把受害者 Discord 绑进攻击者账号的链路），标记寿命与 state cookie 脱钩。
  同日 wrap-up review：前端只在 sessionStorage 存时间戳的 nonce 挡不住 opener 改写 tab 地址，
  改为 render 种 `ih_login_nonce`、回调回显 `&n=`（后端先上线，再上前端）。

## INV-008 · Discord 灰度闸只放行白名单与已有账号，且不得静默失效

- **保护点**：`OAuthController#discordAllowed` / `#configureDiscordAllowlist`
  （`/api/auth/callback/discord` 在换完 token 之后、建号登录之前）。
  `auth.discord.allowlist` 配了非空值即闸启用，只放行两类人：名单内的 Discord id、
  以及 `user_identities` 里已有该身份的回访用户；其余人一律弹回
  `/login?error=discord_canary`，不建号、不发 token，并撤销刚换到的 access token。
- **测试**：`OAuthControllerAllowlistTests`
  （解析畸形取值 / 未配即全开 / 配了却解析不出 id 时拒绝所有人 /
  空 uuid 拒绝 / 已有 identity 放行 / identity 查询失败不放行）
- **为什么**：灰度要拦的是**建新号**——新用户的"验证邮箱→建号"（OTP wiring，INV-012）
  上线之前，放任新用户从 Discord 进来会把已有 GitHub 用户分叉成第二个账号。
  两个方向都必须防住：
  1. **不得静默 fail-open**。闸由一个 env 驱动，缺失/拼错时值为空，行为与"故意 GA"
     完全一致。因此启动时必须播报当前模式（闸启用 N 个 id / 闸关闭全开放），
     否则一次丢掉 `AUTH_DISCORD_ALLOWLIST` 的部署会静默地把 Discord 对全网打开，
     唯一信号是"没有拒绝日志"，而没人在 tail 它。
  2. **配了值却解析不出 id 时必须拒绝所有人**，而不是塌缩成全开放——错误方向要选
     "没人能登"（立刻可见、无损失），不能选"所有人能登"（正是灰度要防的事）。
  另外闸**不能**卡住已有账号的回访登录，否则会把用户锁在自己的账号外面，而提示
  只说"灰度中"，既不告知账号存在也无恢复路径。
- **GA 流程**：OTP wiring 已上线（INV-012：新号一律先验证邮箱，验证出的邮箱已属于别的
  账号即 409 不建号）。GA = `AUTH_DISCORD_ALLOWLIST` 不设或留空（`AUTH_DISCORD_ALLOWLIST=`），
  启动日志应为"闸关闭，Discord 登录对所有人开放"。**不要**写成 `""`、`''` 或 `,`：配了值却
  解析不出 id 是失败关闭（新 Discord 用户全被拒，已有身份不受影响），启动日志报 ERROR。
  **GA 于 2026-10-18 执行。** 闸的代码保留一个版本、处于惰性状态，作为回滚开关：
  要重新关上 Discord 注册，设 `AUTH_DISCORD_ALLOWLIST=<id>` 后重建容器即可（已有 Discord
  身份照常登录）。稳定一个版本后，经 superadmin review 的 PR 退役本条（标 retired）并删掉闸。
- **历史**：2026-07-24 随 Discord 灰度（#53 / involutionhell#389）引入，
  2026-07-25 按 xhigh review（#54）补齐本不变量与测试。2026-10-18 OTP 注册接线、GA。

## INV-009 · docker-compose 的端口映射一律只绑 127.0.0.1

- **保护点**：`docker-compose.yml`（所有服务的 ports 段；目前是 backend 8080、postgres 5432、pgadmin 8082）
- **测试**：`SecurityInvariantsTests#docker_compose里所有端口映射必须绑127_0_0_1`
- **为什么**：公网入口只有 Caddy（Cloudflare → api.involutionhell.com → 127.0.0.1:8080）。
  Docker 发布的端口走 nat 表 DNAT，不经过主机 INPUT 链，主机 iptables 挡不住；
  Oracle 的安全列表也不按端口过滤。backend 原来是 `"8080:8080"`，等于把 Spring 直接
  摆在公网上：绕过 Cloudflare 的防护、明文 HTTP、actuator 等端点谁都能直连。
  Caddy 是 host 网络、ChatBot 跑在宿主机上，都走 127.0.0.1:8080，不需要对外绑定。
  INV-005a 只管 5432，这条覆盖所有端口，以后新加服务也一样。
- **历史**：2026-10-07 在服务器上抓包，3 分钟内有 4 个外网扫描器连到 8080，从公网 IP 访问
  `/actuator/metrics` 返回 200；随本不变量改成 `127.0.0.1:8080:8080`。

## INV-010 · IH 通行证：只给登记过的 client 签码，换码必须 client secret + PKCE

- **保护点**：
  - `sso/service/SsoService#issueCode`（`POST /oauth/sso/code`，需要登录）：client 必须在 `sso.clients`
    表里且已启用；`redirectUri` 与登记值逐字相等（不做前缀、通配，不归一化大小写、尾斜杠、query）；
    只收 `codeChallengeMethod=S256` 和 43 位 base64url 的 `codeChallenge`；disabled 账号 403。
    码存在进程内 Caffeine，60 秒过期。
  - `sso/service/SsoService#authenticateClient`（`POST /internal/sso/token`）：client 已启用，且
    `clientSecret` 用 `MessageDigest.isEqual` 常量时间比较；不过就 401 `invalid_client`，**并且不碰码**。
  - `sso/service/SsoService#redeem`：`asMap().remove` 取码即作废（只能用一次）；clientId、redirectUri、
    `codeVerifier`（格式 + S256 常量时间比较）任一不符，或账号此时已不存在 / 被禁用，都是 400
    `invalid_grant`，码不可重试；成功只返回 `sub/username/displayName/avatarUrl`（`sso/dto/SsoUserInfo`）。
  - `sso/config/SsoProperties.Client#enabled`：secret 或 redirect-uri 为空即未启用，两个接口都拒绝
    （fail closed）；`SsoService` 构造时在启动日志里逐个播报 client 启用与否，不打 secret。
  - `common/config/SaTokenConfigure`：只放行 `/internal/sso/token` 这一条精确路径，`/oauth/sso/code` 不放行。
- **测试**：
  - `SsoControllerIntegrationTests#seedAccountCanSignInAndGetsOnlyTheMinimalProfile`（端到端 + 返回字段白名单）
  - `SsoControllerIntegrationTests#profileNeverCarriesEmailRolesPermissionsOrToken`
  - `SsoControllerIntegrationTests#issuingCodeRequiresLogin`
  - `SsoControllerIntegrationTests#redirectUriMustMatchTheRegisteredValueExactly`
  - `SsoControllerIntegrationTests#unknownClientCannotGetCode`
  - `SsoControllerIntegrationTests#malformedPkceOrStateIsRejected`
  - `SsoControllerIntegrationTests#stateLengthBoundsAreInclusive`（反向）
  - `SsoControllerIntegrationTests#disabledAccountCannotGetCode`
  - `SsoControllerIntegrationTests#clientWithEmptySecretIsRejectedByBothEndpoints`
  - `SsoControllerIntegrationTests#wrongSecretIsInvalidClientAndDoesNotBurnTheCode`
  - `SsoControllerIntegrationTests#codeCanOnlyBeRedeemedOnce`
  - `SsoControllerIntegrationTests#wrongVerifierBurnsTheCode`
  - `SsoControllerIntegrationTests#verifierFormatIsEnforcedEvenWhenItsHashMatches`
  - `SsoControllerIntegrationTests#redirectUriAtRedemptionMustMatchTheCode`
  - `SsoControllerIntegrationTests#anotherClientCannotRedeemTheCode`
  - `SsoControllerIntegrationTests#accountDisabledAfterCodeWasIssuedCannotRedeem`
  - `SsoServiceTests#codeExpiresAfter60Seconds`（假时钟）
  - `SsoServiceTests#clientNeedsBothSecretAndRedirectUriToBeEnabled`
  - `SsoControllerIntegrationTests#stagingClientFromApplicationPropertiesBindsWithItsDashedId`
- **为什么**：签码等于 IH 替用户向别的站点担保"这是谁"，任何一环松了都是账号接管：
  1. redirect_uri 一旦放宽成前缀或通配，攻击者构造 `/sso/authorize?redirect_uri=<自己的地址>`，
     受害者点"继续"，码就直接送到攻击者手里——OAuth 最常见的被打穿方式。
  2. 码会经过地址栏、浏览历史、Referer 和各种日志。PKCE 让偷到码的人换不出东西（verifier 只在
     HoloCard 的 HttpOnly cookie 里）；60 秒过期 + 只能用一次压缩重放窗口；核对不上就作废，不给反复试。
  3. `/internal/sso/token` 必须进 notMatch（调用方没有 IH 登录态），而 Caddy 把所有路径原样转给 8080，
     这条路径公网可达，client secret 是唯一的门：比较要常量时间；secret 为空必须拒绝——空串对空串
     `MessageDigest.isEqual` 判相等，漏配 env 就等于换码接口不设防。启动播报的理由同 INV-008：
     漏配和故意停用行为上一样，只能靠日志区分。没过 client 鉴权的请求不能碰码，否则谁都能烧掉别人的码。
  4. 交出去的资料只够对方建自己的会话。email、角色、权限不给（对方用不上，泄露面越小越好）；
     IH 的 satoken 绝不出 IH——给出去，HoloCard 一旦被攻破就能以用户身份操作 IH。
- **历史**：2026-10-07 随 HoloCard 接入 IH 通行证引入（授权码 + PKCE S256）。client 表：`holocard`（线上）、
  `holocard-staging`（HoloCard 的 staging 跑着没合并的 PR，单独的 secret 和回跳地址，拿到的码换不了线上的会话）。

## INV-011 · schema.sql 不得 seed 可登录的账号

- **保护点**：
  - `src/main/resources/schema.sql`：不含任何 seed 账号的密码哈希；唯一的系统账号 `discord-bridge`
    用 `password_hash='!'` + `enabled=FALSE`，登录不了
  - `docker/init-db/init.sql`、`src/test/resources/test-schema.sql`：admin/alice/auditor 只在这两处 seed
- **测试**：`SecurityInvariantsTests#schema_sql里不允许seed带密码哈希的账号`（扫 schema.sql 里
  `PasswordService.matches` 认的两种格式：bcrypt `$2[aby]$NN$` 和 64 位十六进制的 legacy SHA-256，注释也扫）
- **为什么**：生产 `SPRING_SQL_INIT_MODE=always`，schema.sql 每次启动都跑。seed 写在这里，
  生产库就有 admin/alice/auditor 三个启用账号，密码是 README 里公开的默认值，admin 还带 admin 角色，
  而 `/auth/login` 公网可达、没有限流。AGENTS.md 的"三处 schema 同步"规则在这里是例外：
  seed 不能"同步"回 schema.sql。
- **历史**：2026-10-18 审计发现生产库这三个账号处于启用状态，只读核对确认哈希对得上公开密码。
  本不变量把 seed 移出 schema.sql，只保证以后的启动不再写入；生产里已有的三行要另外处理，
  删掉 seed 不会动它们。

## INV-012 · 新账号只在邮箱 OTP 验证之后建；只有已验证的邮箱进 user_accounts.email

- **保护点**：
  - `usercenter/service/AuthService#loginByProvider`：**从不建号**。身份 → 用户名（仅无身份行的老账号）
    → 已验证邮箱挂靠都没命中时返回 `NeedsRegistration`。`updateProfile` 只收 provider 已验证的邮箱
    （`AuthProvider#isEmailVerified`），未验证的传 null；邮箱挂靠只用已验证邮箱，且不挂进已有同家身份的账号。
  - `usercenter/service/AuthService#completeRegistration`：**唯一的建号入口**，只吃
    `RegistrationService.VerifiedRegistration`。email 写本次 OTP 验证过的地址（不是 provider 预填）；
    角色恰好 `{user}`、无额外权限；建号 + 挂身份 + 登入同一事务。验证出的邮箱已属于任何账号
    （LOWER 匹配）→ `EmailTakenException` → 409，什么都不写、不合并。
  - `usercenter/controller/OAuthController#handleLogin`：新用户 302 `/login/verify`，pendingId 只进
    httpOnly、SameSite=Lax 的 `ih_reg_pending` cookie（Max-Age 900），**绝不进 URL**。
  - `usercenter/controller/AuthController` `/auth/register/{pending,send-otp,verify}`：公开
    （`SaTokenConfigure` notMatch `/auth/register/*`），cookie 是唯一凭据。
  - `usercenter/service/RegistrationService`：会话内至多 5 封、间隔 ≥60s；每个收件邮箱每天 5 封、
    每个第三方身份每天 10 封；累计 5 次错码即作废会话；OTP 10 分钟、会话 15 分钟。
  - `src/main/resources/schema.sql`：`user_accounts` / `user_identities` 没有存 provider token 的列；
    OAuth 换到的 token 只用来取一次资料，拒绝/失败时 `OAuthController#revokeQuietly` 撤销。
- **测试**：
  - `AuthServiceTests#newGithubUserStartsRegistrationInsteadOfCreatingAccount`
  - `AuthServiceTests#unverifiedEmailDoesNotAutoLinkAndStartsRegistration`
  - `AuthServiceTests#returningLoginNeverFillsAnUnverifiedEmail`
  - `AuthServiceTests#verifiedEmailDoesNotAutoLinkIntoAccountThatAlreadyHasThisProvider`
  - `AuthServiceTests#completeRegistrationCreatesAccountWithVerifiedEmailAndIdentity`（email 取 OTP 地址、角色恰好 user）
  - `AuthServiceTests#completeRegistrationRefusesEmailOfExistingAccountWithoutLinking`
  - `RegistrationFlowIntegrationTests#newUserIsSentToVerifyPageWithPendingIdOnlyInHttpOnlyCookie`
  - `RegistrationFlowIntegrationTests#fullRegistrationCreatesAccountWithVerifiedEmailAndLogsIn`（端到端，含角色/权限）
  - `RegistrationFlowIntegrationTests#verifiedEmailOfExistingAccountIsNotMergedAndCreatesNothing`
  - `RegistrationFlowIntegrationTests#wrongCodeIs400UntilLockThen410`
  - `RegistrationFlowIntegrationTests#endpointsArePublicButNeedTheCookie`
  - `RegistrationServiceTests#fifthWrongCodeKillsTheSession_noResendAfterLock`
  - `RegistrationServiceTests#sendsToOneEmailAreCappedAcrossSessions`
  - `RegistrationServiceTests#sendsFromOneIdentityAreCappedAcrossSessions`
  - `OAuthControllerIntegrationTests#unverifiedEmailPlantedViaBindNeverCapturesAVictimsFirstLogin`（攻击链端到端）
  - `OAuthControllerIntegrationTests#verifiedEmailNeverAutoLinksIntoAccountThatAlreadyHasThatProvider`
  - `SecurityInvariantsTests#账号表和身份表不存第三方token`
- **为什么**：`user_accounts.email` 是已验证邮箱自动挂靠的锚点——谁的 provider 已验证邮箱命中它，
  谁就登进这个账号。锚点里混进一个没人验证过的邮箱，就是给"谎称别人邮箱"的第三方号留了接管入口：
  #57 review 复现过"攻击者绑一个报了受害者邮箱的 Discord → 登录把它写进自己的空 email →
  受害者第一次用 GitHub 登录就落进攻击者账号"。所以：
  1. 建号前必须由我们自己的 OTP 证明邮箱归属，且这一步只有一个入口，provider 登录路径上不留第二个建号分支。
  2. pendingId 只在发起人浏览器的 httpOnly cookie 里：放进 URL 就能转发给受害者，让受害者在攻击者的
     注册会话里验证自己的邮箱（预注册劫持）。不要放宽成 SameSite=None。
  3. 验证出的邮箱属于已有账号时只报 409，不合并（ADR-001 红线"禁止静默合并"）；合并账号的唯一路径是
     登录后显式绑定（INV-007）。409 出现在 OTP 通过之后，不能拿来探测哪些邮箱注册过。
  4. 新号角色恰好 `user`：注册入口是公开的，任何角色/权限漂移都直接是越权。
  5. provider token 不落库：落了，拖库就从"拿到一堆资料"升级成"拿着用户的授权去调对方 API"。
- **历史**：2026-10-18 随 OTP 注册接线与 Discord GA 引入（#57 review 的未验证邮箱植入攻击链）。

## INV-013 · 机器人桥接接口不能经代理访问

- **保护点**：`community/controller/SharedLinkInternalController#checkKey`（`/api/community/links/internal`
  的全部三个接口）：请求带 `X-Forwarded-For` 即 404，排在 key 校验与"未配置 503"之前。
- **测试**：
  - `SharedLinkInternalControllerIntegrationTests#submit_viaProxy_returns404`
  - `SharedLinkInternalControllerIntegrationTests#readEndpoints_viaProxy_return404`（含不带头 200 的反向）
- **为什么**：唯一合法调用方 ChatBot 走 127.0.0.1:8080 直连，不带 `X-Forwarded-For`。但
  api.involutionhell.com 的 Caddy 兜底和前端 `/api/community/links/:path*` rewrite 都会把公网请求转到
  这里，二者都会加 `X-Forwarded-For`。不挡的话唯一的门就是 `X-Internal-Key`：key 一旦泄漏（日志、
  ChatBot 配置），谁都能以任意已绑定 Discord 的身份投稿、读审核队列。返回 404 而不是 403，
  从公网看这组接口就不存在。
  依赖的前提：Spring **没有**配 `server.forward-headers-strategy`。配成 `framework` 或 `native` 后，
  `X-Forwarded-For` 会在到达 controller 前被消费掉，本检查静默失效——要开就得同时把这里改成按
  真实对端地址（loopback）判断。
- **历史**：2026-10-18 随 #57 后续加固引入。
