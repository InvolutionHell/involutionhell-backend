package com.involutionhell.backend.posts.service;

import com.involutionhell.backend.common.error.AccessDeniedBusinessException;
import com.involutionhell.backend.common.error.ResourceNotFoundException;
import com.involutionhell.backend.posts.dto.PostRequest;
import com.involutionhell.backend.posts.dto.PostSummaryView;
import com.involutionhell.backend.posts.dto.PostView;
import com.involutionhell.backend.posts.model.Post;
import com.involutionhell.backend.posts.model.PostStatus;
import com.involutionhell.backend.posts.model.PostVisibility;
import com.involutionhell.backend.posts.repository.PostRepository;
import com.involutionhell.backend.usercenter.model.UserAccount;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import com.involutionhell.backend.usercenter.service.UserCenterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 用户原创文章业务服务。
 *
 * 功能覆盖：
 * - create：校验请求、生成唯一 slug、落库
 * - update：owner 校验 + 更新内容字段
 * - delete：owner 校验 + 物理删除
 * - getByAuthorAndSlug：按路由（username + slug）查询详情
 * - listByAuthor：查当前用户自己的所有文章
 * - listFeed：公开 feed 列表（status=PUBLISHED + visibility=PUBLIC）
 * - markPromoted：记录文章转正 PR 链接
 *
 * 跨模块依赖：
 * - PostRepository：数据层，只经过接口，不直接摸 Jdbc 实现
 * - UserCenterService：查作者账号信息（用于视图组装），跨模块只经过 service
 */
@Service
public class PostService {

    private static final Logger log = LoggerFactory.getLogger(PostService.class);

    /** 公开 feed 单次查询最大条数，防止前端传超大 limit 打穿 DB。 */
    private static final int MAX_FEED_LIMIT = 100;

    /** slug 生成：非 ASCII 字母数字替换为连字符，最终去首尾连字符。 */
    private static final Pattern NON_SLUG_CHAR = Pattern.compile("[^a-z0-9]+");

    /** slug 最大长度，超出截断。 */
    private static final int MAX_SLUG_LEN = 100;

    private final PostRepository postRepo;
    /**
     * 跨模块查作者账号信息，通过 UserCenterService facade，不直接摸 repository。
     * 需要 findByUsername（根据 username 路由查文章）。
     */
    private final UserCenterService userCenterService;
    /**
     * 需要按 id 查作者信息以组装视图，UserCenterService 无此 Optional 返回方法，
     * 故直接注入 UserAccountRepository（同一个 Spring context 内部使用，不跨进程）。
     */
    private final UserAccountRepository userAccountRepository;
    private final ObjectMapper objectMapper;

    public PostService(PostRepository postRepo,
                       UserCenterService userCenterService,
                       UserAccountRepository userAccountRepository,
                       ObjectMapper objectMapper) {
        this.postRepo = postRepo;
        this.userCenterService = userCenterService;
        this.userAccountRepository = userAccountRepository;
        this.objectMapper = objectMapper;
    }

    // ========== 写操作 ==========

    /**
     * 创建文章。
     *
     * slug 策略：
     * 1. 前端传了 slug → 直接使用（需不为空字符串）
     * 2. 未传 slug → 由 title 生成 kebab-case 基础 slug
     * 3. 基础 slug 在该作者下已存在 → 追加 -{n}（n 从 2 递增）
     *
     * @param authorId 当前登录用户 id（由 controller 从 StpUtil 取得）
     * @param req      请求体
     * @return 组装好作者信息的详情视图
     */
    @Transactional(rollbackFor = Exception.class)
    public PostView create(Long authorId, PostRequest req) {
        validateRequest(req);

        String slug = resolveSlug(authorId, req.slug(), req.title());

        Post draft = new Post(
                null,
                authorId,
                slug,
                req.title(),
                req.description(),
                req.tags(),
                req.contentMd(),
                req.coverUrl(),
                PostVisibility.PUBLIC,
                PostStatus.PUBLISHED,
                null, null,   // promotedPrUrl / promotedAt
                0,            // viewCount
                null, null    // createdAt / updatedAt（由 DB DEFAULT NOW() 填充）
        );

        Post saved = postRepo.insert(draft);
        log.info("post created: id={} author={} slug={}", saved.id(), authorId, saved.slug());

        return buildView(saved);
    }

    /**
     * 更新文章内容。
     *
     * @param callerId 当前登录用户 id（owner 校验用）
     * @param postId   目标文章 id
     * @param req      更新请求体
     */
    @Transactional(rollbackFor = Exception.class)
    public PostView update(Long callerId, Long postId, PostRequest req) {
        validateRequest(req);

        Post existing = requirePost(postId);
        checkOwner(callerId, existing);

        // slug 处理：前端可传新 slug（改 URL 场景），不传则沿用旧 slug。
        // 必须经 sanitizeSlug 规范化，保证与 create 路径的格式一致（小写、连字符、长度限制）。
        String newSlug = (req.slug() != null && !req.slug().isBlank())
                ? sanitizeSlug(req.slug())
                : existing.slug();

        // slug 冲突检查基于规范化后的值，排除自身（slug 未变则跳过）
        if (!newSlug.equals(existing.slug())) {
            boolean taken = postRepo.findByAuthorAndSlug(callerId, newSlug).isPresent();
            if (taken) {
                throw new DuplicateKeyException("slug 已被使用：" + newSlug);
            }
        }

        String tagsJson = serializeTags(req.tags());
        // update 返回受影响行数；0 表示记录在校验完成后被并发删除
        int affected = postRepo.update(postId, newSlug, req.title(), req.description(),
                tagsJson, req.contentMd(), req.coverUrl());
        if (affected == 0) {
            throw new ResourceNotFoundException("文章已不存在：" + postId);
        }

        log.info("post updated: id={} author={}", postId, callerId);

        return buildView(postRepo.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("文章不存在：" + postId)));
    }

    /**
     * 删除文章（物理删除）。
     *
     * @param callerId 当前登录用户 id（owner 校验用）
     * @param postId   目标文章 id
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long callerId, Long postId) {
        Post existing = requirePost(postId);
        checkOwner(callerId, existing);
        postRepo.delete(postId);
        log.info("post deleted: id={} author={}", postId, callerId);
    }

    /**
     * 记录文章转正 PR 链接。
     * 转正后前端已跳 GitHub 新建文件页，此处只做状态持久化，不阻塞用户操作。
     *
     * @param callerId 当前登录用户 id（owner 校验用）
     * @param postId   目标文章 id
     * @param prUrl    GitHub PR 链接（前端跳转后回传）
     */
    @Transactional(rollbackFor = Exception.class)
    public void markPromoted(Long callerId, Long postId, String prUrl) {
        if (prUrl == null || prUrl.isBlank()) {
            throw new IllegalArgumentException("prUrl 不能为空");
        }
        Post existing = requirePost(postId);
        checkOwner(callerId, existing);
        postRepo.markPromoted(postId, prUrl);
        log.info("post promoted: id={} prUrl={}", postId, prUrl);
    }

    // ========== 读操作 ==========

    /**
     * 按 username + slug 查询文章详情（详情页 / 分享页路由）。
     * 公开接口，匿名可访问（白名单路由）。
     *
     * @param username 作者用户名（URL 路径参数）
     * @param slug     文章 slug
     */
    @Transactional(readOnly = true)
    public Optional<PostView> getByAuthorAndSlug(String username, String slug) {
        // 先查作者，再查文章，避免 authorId 泄漏给 URL
        Optional<UserAccount> author = userCenterService.findByUsername(username);
        if (author.isEmpty()) return Optional.empty();

        return postRepo.findByAuthorAndSlug(author.get().id(), slug)
                .map(p -> PostView.from(p,
                        author.get().username(),
                        author.get().displayName(),
                        author.get().avatarUrl()));
    }

    /**
     * 查询当前登录用户自己的所有文章（含草稿 / 各状态）。
     *
     * @param authorId 当前登录用户 id
     */
    @Transactional(readOnly = true)
    public List<PostSummaryView> listByAuthor(Long authorId) {
        // 查作者信息用于视图组装
        UserAccount author = userAccountRepository.findById(authorId)
                .orElseThrow(() -> new IllegalStateException("author not found: " + authorId));

        return postRepo.findByAuthor(authorId).stream()
                .map(p -> PostSummaryView.from(p,
                        author.username(),
                        author.displayName(),
                        author.avatarUrl()))
                .toList();
    }

    /**
     * 公开 feed 列表（/feed 原创 Tab 使用）。
     * 只返回 status=PUBLISHED + visibility=PUBLIC 的文章，分页。
     *
     * @param limit  每页条数（上限 MAX_FEED_LIMIT）
     * @param offset 偏移量
     */
    @Transactional(readOnly = true)
    public List<PostSummaryView> listFeed(int limit, int offset) {
        int safeLimit  = Math.min(Math.max(limit, 1), MAX_FEED_LIMIT);
        int safeOffset = Math.max(offset, 0);
        // JOIN 查询一次取回作者信息，消除 N+1
        return postRepo.findFeedWithAuthor(safeLimit, safeOffset);
    }

    // ========== 私有工具方法 ==========

    /**
     * 校验创建/更新请求的必填字段。
     */
    private void validateRequest(PostRequest req) {
        if (req == null || req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        if (req.contentMd() == null || req.contentMd().isBlank()) {
            throw new IllegalArgumentException("contentMd 不能为空");
        }
    }

    /**
     * 按 id 查文章，不存在则抛 ResourceNotFoundException（→ 404）。
     */
    private Post requirePost(Long postId) {
        return postRepo.findById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("文章不存在：" + postId));
    }

    /**
     * owner 校验：文章 authorId != callerId 时抛 AccessDeniedBusinessException（→ 403）。
     */
    private void checkOwner(Long callerId, Post post) {
        if (!post.authorId().equals(callerId)) {
            throw new AccessDeniedBusinessException("无权操作他人文章：postId=" + post.id());
        }
    }

    /**
     * 解析最终 slug：优先用前端传入的，否则从 title 生成。
     * 若基础 slug 在作者下已存在，追加 -{n} 直到唯一。
     */
    private String resolveSlug(Long authorId, String requestedSlug, String title) {
        String base;
        if (requestedSlug != null && !requestedSlug.isBlank()) {
            base = sanitizeSlug(requestedSlug);
        } else {
            base = titleToSlug(title);
        }

        // 检查该 slug（或以其为前缀的带数字后缀）是否已存在
        int count = postRepo.countByAuthorAndSlugPrefix(authorId, base);
        if (count == 0) {
            return base;
        }
        // 从 count+1 开始尝试，避免 base-1 被占用后跳号
        for (int i = count + 1; i <= count + 100; i++) {
            String candidate = base + "-" + i;
            // 再精确查一次，防止 countByAuthorAndSlugPrefix 估算偏差
            if (postRepo.findByAuthorAndSlug(authorId, candidate).isEmpty()) {
                return candidate;
            }
        }
        // 极端情况：100 次后仍冲突，加时间戳兜底
        return base + "-" + System.currentTimeMillis();
    }

    /**
     * title → kebab-case slug：
     * 1. Unicode normalize（NFD 去掉变音符）
     * 2. 转小写
     * 3. 非字母数字替换为连字符
     * 4. 首尾连字符去掉
     * 5. 超长截断
     */
    private String titleToSlug(String title) {
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");  // 去掉变音符
        String lower   = normalized.toLowerCase(Locale.ROOT);
        String slugged = NON_SLUG_CHAR.matcher(lower).replaceAll("-");
        String trimmed = slugged.replaceAll("^-+|-+$", "");  // 去首尾连字符
        if (trimmed.isEmpty()) trimmed = "post";
        return trimmed.length() > MAX_SLUG_LEN ? trimmed.substring(0, MAX_SLUG_LEN) : trimmed;
    }

    /**
     * 清理前端传入的 slug，保证只含小写字母、数字和连字符。
     */
    private String sanitizeSlug(String raw) {
        String lower   = raw.trim().toLowerCase(Locale.ROOT);
        String slugged = NON_SLUG_CHAR.matcher(lower).replaceAll("-");
        String trimmed = slugged.replaceAll("^-+|-+$", "");
        if (trimmed.isEmpty()) trimmed = "post";
        return trimmed.length() > MAX_SLUG_LEN ? trimmed.substring(0, MAX_SLUG_LEN) : trimmed;
    }

    /**
     * 将 List<String> tags 序列化为 JSON（给 JdbcPostRepository.update 用）。
     */
    private String serializeTags(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags == null ? List.of() : tags);
        } catch (Exception e) {
            log.warn("serialize tags failed in service, fallback to []: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * 组装 PostView：查作者信息附加在视图上。
     */
    private PostView buildView(Post p) {
        UserAccount author = userAccountRepository.findById(p.authorId()).orElse(null);
        String username    = author != null ? author.username()    : "unknown";
        String displayName = author != null ? author.displayName() : "";
        String avatarUrl   = author != null ? author.avatarUrl()   : null;
        return PostView.from(p, username, displayName, avatarUrl);
    }
}
