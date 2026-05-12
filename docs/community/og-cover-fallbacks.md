# OG 封面抓取兜底链 & 历史数据回填

> 2026-05-12 一次性修复：线上 13 条 APPROVED 分享里 5 条公众号 og_cover 为 NULL、
> 2 条小红书 og_cover 是 `http://` 被浏览器 mixed-content 拦截，整个 feed 卡片
> 几乎全是裂图/占位。本文记录修复方案 + 历史数据回填步骤。

## 病根

`OgFetchService.parseOg` 原本只查 `<meta property="og:image">` 和
`<meta name="twitter:image">`。但：

1. **微信公众号 (mp.weixin.qq.com)** 的 `<head>` 里**没有** og:image，
   封面图埋在 inline `<script>` 的 JS 变量：
   ```html
   <script>
     var msg_cdn_url = "http://mmbiz.qpic.cn/sz_mmbiz_jpg/xxx/0?wx_fmt=jpeg";
     var cdn_url_1_1 = "http://mmbiz.qpic.cn/.../640";
   </script>
   ```
   → Jsoup meta 选择器扫不到，cover 留 NULL。

2. **小红书 (xiaohongshu.com)** 的 og:image 值是
   `http://sns-webpic-qc.xhscdn.com/...`（明明站点是 HTTPS，CDN 也支持 HTTPS，
   就是模板里写了 http）→ feed 卡片用 `<img src="http://...">`，HTTPS 页面会
   被浏览器 mixed-content policy 拦掉。

3. **MAX_BODY_BYTES = 8MB** 偶尔被微信公众号撑爆。公众号在 `</head>` 之前会
   inline 几 MB 的 base64 logo + 编辑器初始化 JSON，命中早停 marker 之前就
   把 8MB 上限读满了 → 整篇 OG 抓取失败。

## 修复

`OgFetchService.parseOg` 现在的 cover 查找顺序：

```
1. <meta property="og:image">             ← 标准 OG
2. <meta name="twitter:image">            ← Twitter Card 兜底
3. var msg_cdn_url / cdn_url_1_1          ← WeChat fallback（findWeixinCover）
4. http:// → https:// 升级                ← upgradeMediaProtocol
```

WeChat 正则强约束开头必须是 `http(s)://`，杜绝 `javascript:` 等 XSS 注入。

`MAX_BODY_BYTES` 提到 16MB。如果将来还有站点撑爆 16MB，得改走图片代理方案
（见下文「长期方向」）。

前端 `lib/url-safety.ts` 的 `sanitizeMediaUrl` 也加了 defense-in-depth 的
http→https 升级 —— 万一历史数据或 LLM 兜底回填漏了 https，前端再升一次。

## 回填生产已有数据

代码上线后**新提交的分享会自动走新逻辑**，但已经入库的 NULL / http:// 数据
不会自动重抓。三种回填方式按工作量从小到大：

### 方式 A：SQL 直接升级 http→https（最快，覆盖 2/13）

```sql
-- 直接把 og_cover 是 http:// 的升级成 https://
UPDATE shared_links
SET og_cover = 'https://' || substr(og_cover, 8),
    updated_at = now()
WHERE og_cover LIKE 'http://%';
```

### 方式 B：通过 admin 重抓 API 触发完整 enrichment（覆盖 5/13 NULL 公众号）

`/api/admin/community/links/{id}/refetch-og` 端点（M2 PR #23 加的）会重跑
`OgFetchService` + `OgFallbackService`，能把 NULL 的 og_cover 补上。

```bash
# 拿到所有 og_cover 为 NULL 的 APPROVED 链接 id
docker exec involution-postgres psql -U neondb_owner -d involution_hell -At \
  -c "SELECT id FROM shared_links WHERE status='APPROVED' AND og_cover IS NULL;"

# 对每个 id 调 admin refetch（需要 admin satoken cookie）
for id in 28 26 25 24 22 20; do
  curl -X POST "https://api.involutionhell.com/api/admin/community/links/$id/refetch-og" \
    -H "Cookie: satoken=<your-admin-token>"
done
```

### 方式 C：写个 Spring `CommandLineRunner` 一次性扫库重抓

如果回填规模大，可以加一个 profile=backfill-og 才启用的 runner，
启动时扫所有 `og_cover IS NULL OR og_cover LIKE 'http://%'` 的行，
逐条丢给 `SharedLinkEnrichmentWorker.enqueue(id)`。本次只有 7 条，方式 A+B 足够。

## 长期方向：图片代理

即便修好了抓取阶段，浏览器端拉 mmbiz.qpic.cn / xhscdn 还要担心：
- 防盗链：微信 mmbiz.qpic.cn 会按 Referer 判定，`referrerPolicy="no-referrer"`
  目前能绕，但腾讯哪天收紧立刻全裂
- 跨域 CDN 偶发 timeout / 403
- 用户上微信公众号「外部链接保护」改 URL 后历史 cover 失效

更稳的方案是自建图片代理 `/api/og-image?u=<encoded-url>`：
- 后端拉图（带不带 Referer 自己控）
- 走 R2 / 本地磁盘缓存（7 天 TTL）
- 返回 image/* + 强缓存 header

工程量：~150 行后端 + 改前端 LinkCard 的 src。下一个 milestone 再做。

## 测试

`OgFetchServiceTests` 新增 4 个用例：

- `parseOg_weixinNoOgImage_fallsBackToMsgCdnUrl` —— 公众号 head 没 og:image
  时从 inline script 兜底，且协议升级到 https
- `parseOg_httpOgImage_upgradesToHttps` —— og:image 是 http:// 自动升级
- `upgradeMediaProtocol_handlesCaseAndIdempotency` —— 协议升级幂等 & 大小写不敏感
- `findWeixinCover_picksFirstMatchAndIgnoresOtherVars` —— 正则白名单 & XSS 拦截

跑：`./mvnw -Dtest='OgFetchServiceTests' test`
