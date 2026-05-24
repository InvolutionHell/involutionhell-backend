package com.involutionhell.backend.docs.controller;

import com.involutionhell.backend.docs.service.DocPathService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;

/**
 * 文档路径解析端点。
 *
 * GET /api/docs/resolve?path=/zh/docs/community/dev-tips/git101
 * → 301 Location: /docs/community/dev-tips/git101   （canonical，无 locale）
 * → 404（路径不认识）
 *
 * canonical 不带 locale 前缀，前端 Block 3 负责拼 locale 后再跳转。
 * 公开端点，无需登录（已加入 SaTokenConfigure 白名单）。
 */
@RestController
public class DocsResolveController {

    private final DocPathService docPathService;

    public DocsResolveController(DocPathService docPathService) {
        this.docPathService = docPathService;
    }

    @GetMapping("/api/docs/resolve")
    public ResponseEntity<Void> resolve(@RequestParam String path) {
        Optional<String> canonical = docPathService.resolveCanonical(path);
        if (canonical.isPresent()) {
            return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                    .location(URI.create(canonical.get()))
                    .build();
        }
        return ResponseEntity.notFound().build();
    }
}
