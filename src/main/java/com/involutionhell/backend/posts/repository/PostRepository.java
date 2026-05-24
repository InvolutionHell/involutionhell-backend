package com.involutionhell.backend.posts.repository;

import com.involutionhell.backend.posts.model.Post;

import java.util.List;
import java.util.Optional;

/**
 * posts 仓库接口。
 *
 * 实现由 JdbcPostRepository 提供（裸 JDBC，与 JdbcSharedLinkRepository 同范式）。
 * Service 层不直接依赖实现类，便于未来测试替换。
 */
public interface PostRepository {

    /** 插入新文章，返回带 id 的完整对象。 */
    Post insert(Post draft);

    /** 按 id 查找文章。 */
    Optional<Post> findById(Long id);

    /** 按作者 id + slug 查找（用于详情页路由：/u/{username}/posts/{slug}）。 */
    Optional<Post> findByAuthorAndSlug(Long authorId, String slug);

    /** 查询某作者所有文章（全状态），按 created_at DESC。 */
    List<Post> findByAuthor(Long authorId);

    /**
     * 公开 feed 列表：status=PUBLISHED + visibility=PUBLIC，按 created_at DESC 分页。
     * limit 上限由 Service 层限定，避免超大 offset 拖垮 DB。
     */
    List<Post> findFeed(int limit, int offset);

    /** 按 slug 前缀统计同作者已存在的文章数（slug 去重时用）。 */
    int countByAuthorAndSlugPrefix(Long authorId, String slugPrefix);

    /** 更新文章内容字段（title/description/tags/contentMd/coverUrl/slug）。 */
    void update(Long id, String slug, String title, String description,
                String tagsJson, String contentMd, String coverUrl);

    /** 删除文章（物理删除）。 */
    void delete(Long id);

    /** 记录转正：写入 promoted_pr_url + promoted_at=NOW()。 */
    void markPromoted(Long id, String prUrl);
}
