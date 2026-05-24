package com.involutionhell.backend.posts.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.posts.dto.PostRequest;
import com.involutionhell.backend.posts.dto.PostSummaryView;
import com.involutionhell.backend.posts.dto.PostView;
import com.involutionhell.backend.posts.service.PostService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户原创文章接口（posts 模块）。
 *
 * 路由总览：
 * POST   /api/posts               - 创建文章（需登录）
 * PUT    /api/posts/{id}          - 更新文章（需登录 + owner）
 * DELETE /api/posts/{id}          - 删除文章（需登录 + owner）
 * GET    /api/posts/mine          - 我的文章（需登录）
 * GET    /api/posts/feed          - 公开 feed 列表（匿名可访问）
 * GET    /api/posts/{username}/{slug} - 详情/分享页（匿名可访问）
 * POST   /api/posts/{id}/promote  - 记录转正 PR（需登录 + owner）
 *
 * 公开读路由（feed / 详情）在 SaTokenConfigure 白名单里放行。
 * 写路由由方法级 @SaCheckLogin 守卫。
 */
@RestController
@RequestMapping("/api/posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    /**
     * 创建文章。
     * 作者 id 从 Sa-Token 登录态取得，不接受前端传入（防伪造）。
     */
    @PostMapping
    @SaCheckLogin
    public ResponseEntity<ApiResponse<PostView>> create(@RequestBody PostRequest req) {
        long authorId = StpUtil.getLoginIdAsLong();
        PostView view = postService.create(authorId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(view));
    }

    /**
     * 更新文章内容。
     * Service 层做 owner 校验，非作者返回 403。
     */
    @PutMapping("/{id}")
    @SaCheckLogin
    public ApiResponse<PostView> update(@PathVariable Long id,
                                        @RequestBody PostRequest req) {
        long callerId = StpUtil.getLoginIdAsLong();
        PostView view = postService.update(callerId, id, req);
        return ApiResponse.ok(view);
    }

    /**
     * 删除文章（物理删除）。
     * Service 层做 owner 校验，非作者返回 403。
     */
    @DeleteMapping("/{id}")
    @SaCheckLogin
    public ApiResponse<Void> delete(@PathVariable Long id) {
        long callerId = StpUtil.getLoginIdAsLong();
        postService.delete(callerId, id);
        return ApiResponse.okMessage("deleted");
    }

    /**
     * 查询我的所有文章（全状态，含草稿）。
     */
    @GetMapping("/mine")
    @SaCheckLogin
    public ApiResponse<List<PostSummaryView>> mine() {
        long authorId = StpUtil.getLoginIdAsLong();
        return ApiResponse.ok(postService.listByAuthor(authorId));
    }

    /**
     * 公开 feed 列表（/feed 原创 Tab 使用）。
     * 只返回 PUBLISHED + PUBLIC 的文章，支持分页。
     */
    @GetMapping("/feed")
    public ApiResponse<List<PostSummaryView>> feed(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0")  int offset) {
        return ApiResponse.ok(postService.listFeed(limit, offset));
    }

    /**
     * 文章详情/分享页（公开，匿名可访问）。
     * 路径 /api/posts/{username}/{slug} 对应前端 /u/{username}/posts/{slug}。
     */
    @GetMapping("/{username}/{slug}")
    public ResponseEntity<ApiResponse<PostView>> detail(
            @PathVariable String username,
            @PathVariable String slug) {
        Optional<PostView> result = postService.getByAuthorAndSlug(username, slug);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse<>(false, "文章不存在", null));
        }
        return ResponseEntity.ok(ApiResponse.ok(result.get()));
    }

    /**
     * 记录文章转正 PR 链接。
     * 前端点"转正"按钮后跳 GitHub 新建文件页，完成后回传 PR URL。
     *
     * 请求体：{"prUrl": "https://github.com/..."}
     */
    @PostMapping("/{id}/promote")
    @SaCheckLogin
    public ApiResponse<Void> promote(@PathVariable Long id,
                                     @RequestBody Map<String, String> body) {
        long callerId = StpUtil.getLoginIdAsLong();
        String prUrl = body != null ? body.get("prUrl") : null;
        postService.markPromoted(callerId, id, prUrl);
        return ApiResponse.okMessage("promoted");
    }
}
