-- ---------------------------------------------------------------------------
-- One-off 数据迁移：把 2026-04-19 IA 重组（commit 6684884）之前的旧文件路径
-- 补齐进 doc_paths，让榜单 / rank 接口在 30D / ALL 窗口能命中 GA4 里的历史 pagePath。
--
-- 为什么需要：
--   scripts/backfill-contributors.mjs 只对"当前文件"做 upsertDocPath，
--   如果某一轮 backfill 是在 IA 重组之后才跑起来（例如 Neon→自建 PG 迁移后首次跑），
--   老前缀的 doc_paths 行就丢了，GA4 里残留的 /docs/ai/* 之类 pagePath 就永远 join 不上。
--
-- 为什么只覆盖前缀 wildcard，不覆盖 next.config.mjs 里的点状 redirect：
--   点状 redirect（swanlab / 部分 cpp_backend 重命名）单文件流量很小，漏掉一两条
--   不影响榜单完整性；前缀型 wildcard 覆盖的老路径才是 30D / ALL 窗口真正的"大头"。
--
-- Leetcode 仍有已知局限：
--   app/docs/career/interview-prep/leetcode/*.md 文件名仍是中文（如"平衡二叉树.md"），
--   但 URL 会被 lib/source.ts 转成拼音 slug。因此 GA4 命中的 pagePath（拼音）
--   与 docs.path_current（中文文件名）本就无法直接 join，这是后续独立 issue。
--
-- 幂等性：
--   INSERT ... ON CONFLICT (doc_id, path) DO NOTHING；反复跑安全。
--
-- 使用方式（一次性执行，不走 /docker-entrypoint-initdb.d 自动流程）：
--   docker exec -i involution-postgres psql -U neondb_owner -d involution_hell \
--     < backend/docs/migrations/2026-04-22-seed-ia-reorg-doc-paths.sql
--
--   本地 dev 新拉 docker 起 pg 时不需要跑——docs 表是空的，跑了也是 no-op；
--   等 scripts/backfill-contributors.mjs 灌完数据再跑即可。
-- ---------------------------------------------------------------------------

-- 用 ROW_NUMBER 按 new_prefix 长度取最长前缀，避免 /career/interview-prep/leetcode/
-- 同时被 /career/interview-prep/ 规则命中，多插一条错误的 jobs/interview-prep/leetcode/ 别名。
WITH ia_reorg_aliases(new_prefix, old_prefix) AS (
    VALUES
        -- CommunityShare 拆分到 career / community / learn
        ('app/docs/career/interview-prep/leetcode/',                  'app/docs/CommunityShare/Leetcode/'),
        ('app/docs/community/language/',                              'app/docs/CommunityShare/Language/'),
        ('app/docs/community/life/',                                  'app/docs/CommunityShare/Life/'),
        ('app/docs/community/mental-health/',                         'app/docs/CommunityShare/MentalHealth/'),
        ('app/docs/community/dev-tips/',                              'app/docs/CommunityShare/Geek/'),
        ('app/docs/community/tools/',                                 'app/docs/CommunityShare/Amazing-AI-Tools/'),
        ('app/docs/learn/ai/reinforcement-learning/',                 'app/docs/CommunityShare/Personal-Study-Notes/Reinforcement-Learning/'),
        ('app/docs/learn/ai/foundation-models/rag/',                  'app/docs/CommunityShare/RAG/'),
        -- 顶层目录重命名
        ('app/docs/projects/',                                        'app/docs/all-projects/'),
        ('app/docs/learn/ai/',                                        'app/docs/ai/'),
        ('app/docs/learn/cs/',                                        'app/docs/computer-science/'),
        -- jobs → career
        ('app/docs/career/interview-prep/',                           'app/docs/jobs/interview-prep/'),
        ('app/docs/career/events/',                                   'app/docs/jobs/event-keynote/')
),
ranked_matches AS (
    SELECT d.id                                                      AS doc_id,
           a.old_prefix || substring(d.path_current FROM length(a.new_prefix) + 1)
                                                                     AS old_path,
           ROW_NUMBER() OVER (
               PARTITION BY d.id
               ORDER BY length(a.new_prefix) DESC
           )                                                         AS rn
    FROM docs d
    JOIN ia_reorg_aliases a ON d.path_current LIKE a.new_prefix || '%'
    WHERE d.path_current IS NOT NULL
)
INSERT INTO doc_paths (doc_id, path)
SELECT doc_id, old_path
FROM ranked_matches
WHERE rn = 1
ON CONFLICT (doc_id, path) DO NOTHING;
