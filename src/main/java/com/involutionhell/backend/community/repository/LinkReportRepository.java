package com.involutionhell.backend.community.repository;

import com.involutionhell.backend.community.model.LinkReport;

/**
 * 举报仓库。M1 只落骨架接口。
 *
 * insert 依赖 DB (link_id, reporter_id) UNIQUE 约束保证幂等：
 * - 同一人重复举报同一条 → Service 捕获 DuplicateKey 静默成功，不报错给前端
 */
public interface LinkReportRepository {

    /** 写入举报，违反 UNIQUE 约束时抛出 Spring DuplicateKeyException。 */
    LinkReport insert(LinkReport draft);

    int countByLinkId(Long linkId);
}
