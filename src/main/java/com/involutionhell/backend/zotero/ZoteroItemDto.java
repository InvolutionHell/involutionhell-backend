package com.involutionhell.backend.zotero;

/**
 * 用户 pinned_papers 里 itemKey 关联出来的一条 Zotero 元信息。
 * 字段对齐前端 UserPaperItem，便于直接塞进 pinned_papers 数据流。
 *
 * @param itemKey          Zotero item key（A1B2C3D4 格式）
 * @param title            文章标题
 * @param authors          作者列表拼接字符串，"LastName, FirstName; LastName2, ..."
 * @param year             出版年（从 date 字段提取），可能为空
 * @param url              原文链接或 Zotero 详情页
 * @param abstractNote     摘要
 * @param publicationTitle 期刊/会议名
 */
public record ZoteroItemDto(
        String itemKey,
        String title,
        String authors,
        String year,
        String url,
        String abstractNote,
        String publicationTitle
) {}
