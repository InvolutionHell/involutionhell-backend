package com.involutionhell.backend.docs.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocHistoryControllerTests {

    @Test
    void fumadocsFilePath_mapsToContentDocs() {
        // 前端 DocHistoryPanel 传的是 page.file.path（相对 content/docs/）
        assertThat(DocHistoryController.normalizeDocsPath("learn/ai/index.mdx"))
                .isEqualTo("content/docs/learn/ai/index.mdx");
        assertThat(DocHistoryController.normalizeDocsPath("/docs/learn/ai/index.mdx"))
                .isEqualTo("content/docs/learn/ai/index.mdx");
        assertThat(DocHistoryController.normalizeDocsPath("content/docs/learn/ai/index.mdx"))
                .isEqualTo("content/docs/learn/ai/index.mdx");
    }

    @Test
    void pathsOutsideContentDocs_rejected() {
        assertThat(DocHistoryController.normalizeDocsPath("content/../.env")).isNull();
        assertThat(DocHistoryController.normalizeDocsPath("content/secrets.md")).isNull();
        assertThat(DocHistoryController.normalizeDocsPath("learn\\ai")).isNull();
        assertThat(DocHistoryController.normalizeDocsPath(" ")).isNull();
    }
}
