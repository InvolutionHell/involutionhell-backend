package com.involutionhell.backend.docs.controller;

import com.involutionhell.backend.support.AbstractWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/docs/resolve 集成测试（H2，PostgreSQL 兼容模式）。
 *
 * 回归点：
 *   1. 历史路径（doc_paths.path，app/ 前缀）→ 当前路径（docs.path_current，content/ 前缀）的 301。
 *   2. path_current 指向翻译版文件（leworldmodel.en.md）时，canonical 不能漏出 ".en" 后缀。
 *   3. 带 locale 前缀（/zh、/en）的输入与无前缀输入命中同一条 canonical。
 *   4. 未知路径 404。
 *
 * 每个用例用互不相同的输入路径，避免 @Cacheable("doc-resolve") 在共享 context 里跨用例串味。
 */
class DocsResolveControllerIntegrationTests extends AbstractWebIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 共享 @SpringBootTest context + H2 DB_CLOSE_DELAY=-1：每个用例前清表，避免本类 seed 的行
    // 残留污染后续读 docs/doc_paths 的测试类（与 AnalyticsServiceGetTopDocsIntegrationTests 同款）。
    @BeforeEach
    void cleanDocsTables() {
        jdbcTemplate.update("DELETE FROM doc_paths");
        jdbcTemplate.update("DELETE FROM docs");
    }

    private void seedDoc(String id, String pathCurrent, String historicalPath) {
        jdbcTemplate.update(
                "INSERT INTO docs (id, path_current, title) VALUES (?, ?, ?)",
                id, pathCurrent, id);
        if (historicalPath != null) {
            jdbcTemplate.update(
                    "INSERT INTO doc_paths (doc_id, path) VALUES (?, ?)",
                    id, historicalPath);
        }
    }

    @Test
    void translatedDocDoesNotLeakLocaleSuffixIntoCanonical() throws Exception {
        seedDoc(
                "doc-leworldmodel",
                "content/docs/learn/ai/papers/leworldmodel.en.md",
                "app/docs/community/papers/leworldmodel.md");

        mockMvc.perform(get("/api/docs/resolve").param("path", "/zh/docs/community/papers/leworldmodel"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/docs/learn/ai/papers/leworldmodel"));
    }

    @Test
    void reclassifiedDocResolvesHistoricalPathToCurrent() throws Exception {
        seedDoc(
                "doc-git101",
                "content/docs/learn/cs/dev-tips/git101.mdx",
                "app/docs/community/dev-tips/git101.mdx");

        mockMvc.perform(get("/api/docs/resolve").param("path", "/en/docs/community/dev-tips/git101"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/docs/learn/cs/dev-tips/git101"));
    }

    @Test
    void translatedIndexStripsLocaleAndIndexSuffix() throws Exception {
        seedDoc(
                "doc-ds-index",
                "content/docs/learn/cs/data-structures/index.en.mdx",
                "app/docs/old/data-structures/index.mdx");

        mockMvc.perform(get("/api/docs/resolve").param("path", "/docs/old/data-structures"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "/docs/learn/cs/data-structures"));
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mockMvc.perform(get("/api/docs/resolve").param("path", "/docs/does/not/exist"))
                .andExpect(status().isNotFound());
    }
}
