package com.involutionhell.backend.events.repository;

import com.involutionhell.backend.events.model.Event;

import java.util.List;
import java.util.Optional;

/**
 * 活动数据访问接口。
 *
 * 设计原则：
 * - 读接口提供不同 status 过滤维度，让前端列表页 / 管理员后台各取所需
 * - 写接口返回完整 Event（insert 后回读，避免前端再发一次 GET）
 * - 不提供 findAll 不带过滤的版本：避免未来 events 表膨胀时被无脑全量拉
 */
public interface EventRepository {

    /** 按 id 查找单条活动。管理员编辑页 / 详情页用。 */
    Optional<Event> findById(Long id);

    /** 仅 published + archived 的活动，给公开列表页用。按 startTime 倒序（NULL 最靠后）。 */
    List<Event> findPublic();

    /** 所有状态（含 draft / cancelled）的活动。仅 admin 后台用。 */
    List<Event> findAllForAdmin();

    /** 创建活动。返回带生成 id / createdAt 的完整对象。 */
    Event insert(Event event);

    /** 更新活动。updatedAt 由数据库侧自动刷新；returned 对象里的 updatedAt 是最新值。 */
    Event update(Event event);

    /** 删除活动。级联 event_interests 由外键 ON DELETE CASCADE 处理。 */
    void deleteById(Long id);
}
