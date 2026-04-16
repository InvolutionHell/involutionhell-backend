package com.involutionhell.backend.zotero;

import com.involutionhell.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 批量按 itemKey 拉 Zotero group 里的文献元信息。
 * 给个人主页 pinned_papers 用（用户只存 itemKey，运行时由这个接口补齐 title/authors 等）。
 */
@RestController
@RequestMapping("/api/user-center/zotero")
public class ZoteroController {

    private final ZoteroService zoteroService;

    public ZoteroController(ZoteroService zoteroService) {
        this.zoteroService = zoteroService;
    }

    /**
     * @param keys    逗号分隔的 itemKey 列表，例如 "ABCD1234,EFGH5678"
     * @param groupId 可选，覆盖默认 group（默认走 application.properties ZOTERO_GROUP_ID）
     */
    @GetMapping("/items")
    public ApiResponse<List<ZoteroItemDto>> getItems(
            @RequestParam(name = "keys") String keys,
            @RequestParam(name = "groupId", required = false, defaultValue = "0") long groupId
    ) {
        if (keys == null || keys.isBlank()) {
            return ApiResponse.ok(List.of());
        }
        List<String> parsed = Stream.of(keys.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (parsed.isEmpty()) return ApiResponse.ok(List.of());
        // 限制一次最多 100 个 key，防滥用
        if (parsed.size() > 100) parsed = parsed.subList(0, 100);
        return ApiResponse.ok(zoteroService.getItems(groupId, parsed));
    }
}
