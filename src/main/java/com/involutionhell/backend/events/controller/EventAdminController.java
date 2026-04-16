package com.involutionhell.backend.events.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.events.dto.EventRequest;
import com.involutionhell.backend.events.dto.EventView;
import com.involutionhell.backend.events.model.Event;
import com.involutionhell.backend.events.service.EventService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * 活动管理接口（需要 admin 角色）。
 *
 * 路由（全部需要 admin）：
 * - GET    /api/admin/events        全量列表（含 draft / cancelled）
 * - GET    /api/admin/events/{id}   单条详情（管理员可看 draft）
 * - POST   /api/admin/events        创建
 * - PUT    /api/admin/events/{id}   更新
 * - DELETE /api/admin/events/{id}   删除（级联删 event_interests）
 *
 * 使用 Sa-Token 的 @SaCheckRole("admin") 做整个类级别保护。拦截器链上会先做登录校验，
 * 再做角色校验，所以匿名访问返回 401，已登录非 admin 返回 403。
 */
@RestController
@RequestMapping("/api/admin/events")
@SaCheckRole("admin")
public class EventAdminController {

    private final EventService eventService;

    public EventAdminController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public ApiResponse<List<EventView>> list() {
        List<Event> events = eventService.listAllForAdmin();
        List<EventView> views = events.stream()
                .map(e -> EventView.from(e, eventService.countInterest(e.id())))
                .toList();
        return ApiResponse.ok(views);
    }

    @GetMapping("/{id}")
    public ApiResponse<EventView> detail(@PathVariable Long id) {
        Optional<Event> maybe = eventService.findById(id);
        if (maybe.isEmpty()) return new ApiResponse<>(false, "活动不存在", null);
        long interest = eventService.countInterest(id);
        return ApiResponse.ok(EventView.from(maybe.get(), interest));
    }

    @PostMapping
    public ApiResponse<EventView> create(@RequestBody EventRequest req) {
        String validationError = validate(req);
        if (validationError != null) return new ApiResponse<>(false, validationError, null);

        long organizerId = StpUtil.getLoginIdAsLong();
        Event draft = req.toEvent(null, organizerId, null, null);
        Event created = eventService.create(draft);
        return ApiResponse.ok("活动已创建", EventView.from(created, 0));
    }

    @PutMapping("/{id}")
    public ApiResponse<EventView> update(@PathVariable Long id, @RequestBody EventRequest req) {
        String validationError = validate(req);
        if (validationError != null) return new ApiResponse<>(false, validationError, null);

        Optional<Event> existing = eventService.findById(id);
        if (existing.isEmpty()) return new ApiResponse<>(false, "活动不存在", null);

        // 保留原 organizerId（不允许更新时转让组织方；要转让走独立接口，避免误操作）
        Long originalOrganizer = existing.get().organizerId();
        Event updated = req.toEvent(id, originalOrganizer, existing.get().createdAt(), null);
        Event saved = eventService.update(updated);
        long interest = eventService.countInterest(id);
        return ApiResponse.ok("活动已更新", EventView.from(saved, interest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        Optional<Event> existing = eventService.findById(id);
        if (existing.isEmpty()) return new ApiResponse<>(false, "活动不存在", null);
        eventService.delete(id);
        return ApiResponse.okMessage("活动已删除");
    }

    /**
     * 基础字段校验。返回 null 表示校验通过，否则返回错误信息。
     * 不用 @Valid + JSR-380 是因为项目当前没引入 spring-boot-starter-validation，
     * 避免为这一个模块引进新依赖。
     */
    private String validate(EventRequest req) {
        if (req == null) return "请求体不能为空";
        if (req.title() == null || req.title().isBlank()) return "title 不能为空";
        if (req.status() != null && !List.of("draft", "published", "archived", "cancelled").contains(req.status())) {
            return "status 必须是 draft / published / archived / cancelled 之一";
        }
        if (req.startTime() != null && req.endTime() != null && req.endTime().isBefore(req.startTime())) {
            return "endTime 不能早于 startTime";
        }
        return null;
    }
}
