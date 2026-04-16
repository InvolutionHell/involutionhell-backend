package com.involutionhell.backend.events.service;

import com.involutionhell.backend.events.model.Event;
import com.involutionhell.backend.events.repository.EventInterestRepository;
import com.involutionhell.backend.events.repository.EventRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 活动业务服务。主要职责：
 * - 公开读 / 管理员读 / 写 的薄封装（大部分直接委托 repository）
 * - 未来要加的非 CRUD 逻辑（比如按 tag 过滤、和 /docs/jobs/event-keynote 的回放反向关联）
 *   都放这里，避免逻辑塞 Controller
 * - 感兴趣功能同样委托给 EventInterestRepository，但统计数由这里对外暴露，避免
 *   Controller 同时依赖 Event + EventInterest 两个 repo
 */
@Service
public class EventService {

    private final EventRepository eventRepository;
    private final EventInterestRepository interestRepository;

    public EventService(EventRepository eventRepository,
                        EventInterestRepository interestRepository) {
        this.eventRepository = eventRepository;
        this.interestRepository = interestRepository;
    }

    /** 公开列表。仅 published / archived 状态。 */
    public List<Event> listPublic() {
        return eventRepository.findPublic();
    }

    /** 管理员列表。全状态。 */
    public List<Event> listAllForAdmin() {
        return eventRepository.findAllForAdmin();
    }

    /** 查单条。公开读 / 管理员读公用这一个方法；权限过滤放 Controller 做。 */
    public Optional<Event> findById(Long id) {
        return eventRepository.findById(id);
    }

    /** 创建活动。id / createdAt / updatedAt 由 DB 生成。 */
    public Event create(Event draft) {
        return eventRepository.insert(draft);
    }

    /** 更新活动。调用前必须已校验 id 存在（由 Controller 做 404 返回）。 */
    public Event update(Event event) {
        return eventRepository.update(event);
    }

    /** 删除活动。ON DELETE CASCADE 会清理 event_interests。 */
    public void delete(Long id) {
        eventRepository.deleteById(id);
    }

    /** 某活动当前"感兴趣"人数。公开接口使用。 */
    public long countInterest(long eventId) {
        return interestRepository.countByEvent(eventId);
    }

    /** 当前登录用户是否对某活动感兴趣。匿名调用方需短路传 false，不要调这个。 */
    public boolean isInterested(long eventId, long userId) {
        return interestRepository.isInterested(eventId, userId);
    }

    /** 登录用户表达"感兴趣"。幂等。 */
    public void markInterested(long eventId, long userId) {
        interestRepository.add(eventId, userId);
    }

    /** 登录用户取消"感兴趣"。幂等。 */
    public void unmarkInterested(long eventId, long userId) {
        interestRepository.remove(eventId, userId);
    }
}
