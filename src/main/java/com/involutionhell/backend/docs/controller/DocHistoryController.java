package com.involutionhell.backend.docs.controller;

import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.docs.dto.DocHistoryItemDto;
import com.involutionhell.backend.docs.service.DocHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文档修改历史公开接口。前端 fumadocs 页底部 DocHistoryPanel 组件消费。
 * 路径规范化逻辑留在这里而不是 Service，避免 @Cacheable 接收未规范化的 key。
 */
@RestController
@RequestMapping("/api/docs")
public class DocHistoryController {

    private final DocHistoryService docHistoryService;

    public DocHistoryController(DocHistoryService docHistoryService) {
        this.docHistoryService = docHistoryService;
    }

    /**
     * 规范化前端传入的文档路径为仓库根相对路径（GitHub API 要求）。
     *
     * 接受的输入形态：
     * - "app/docs/ai/..."        仓库根相对，原样返回
     * - "docs/ai/..."            前面补 "app/"
     * - "/docs/ai/..."           URL 风格，去开头斜杠再补 "app/"
     * - "ai/rl/index.mdx"        fumadocs page.file.path 风格，补 "app/docs/"
     *
     * 拒绝：含 ".."、反斜杠、null 字节；最终不落在 app/docs/ 下一律拒绝，
     * 避免服务端 GITHUB_TOKEN 被借来拉仓库内任意文件的 commit。
     */
    static String normalizeDocsPath(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.contains("..") || raw.contains("\\") || raw.contains("\0")) return null;

        String normalized = raw;
        if (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.startsWith("docs/")) normalized = "app/" + normalized;
        // fumadocs page.file.path 返回相对 app/docs/ 的路径，补前缀
        if (!normalized.startsWith("app/")) normalized = "app/docs/" + normalized;
        if (!normalized.startsWith("app/docs/")) return null;
        return normalized;
    }

    /**
     * 拉指定文档的最近 5 条 commit 历史。结果 Caffeine 缓存 10 分钟。
     * 不要求登录，SaToken 白名单放行。
     */
    @GetMapping("/history")
    public ApiResponse<List<DocHistoryItemDto>> getHistory(@RequestParam("path") String rawPath) {
        String path = normalizeDocsPath(rawPath);
        if (path == null) {
            return new ApiResponse<>(false, "缺少合法的 path 参数（仅允许 app/docs/ 路径）", null);
        }
        List<DocHistoryItemDto> items = docHistoryService.getHistory(path);
        return ApiResponse.ok(items);
    }
}
