package com.involutionhell.backend.events.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.events.dto.EventView;
import com.involutionhell.backend.events.model.Event;
import com.involutionhell.backend.events.service.EventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 活动公开读接口（匿名可访问）。
 *
 * 路由：
 * - GET /api/events           公开列表（published + archived）
 * - GET /api/events/{id}      单条详情（含"感兴趣"统计 + 当前用户是否感兴趣）
 *
 * SaToken 白名单配置见 SaTokenConfigure.java。
 * 单条接口里读取"当前用户"时用 StpUtil.isLogin() 短路——匿名用户时不报错，只是
 * interested 字段返回 false。
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public ApiResponse<List<EventView>> list() {
        List<Event> events = eventService.listPublic();
        List<EventView> views = events.stream()
                .map(e -> EventView.from(e, eventService.countInterest(e.id())))
                .toList();
        return ApiResponse.ok(views);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        Optional<Event> maybe = eventService.findById(id);
        if (maybe.isEmpty()) return new ApiResponse<>(false, "活动不存在", null);
        Event event = maybe.get();
        // draft 状态不对外公开（即使直接访问 /api/events/{id} 也返回 404 语义）
        if ("draft".equals(event.status())) {
            return new ApiResponse<>(false, "活动不存在", null);
        }

        long interestCount = eventService.countInterest(id);
        boolean interested = false;
        if (StpUtil.isLogin()) {
            long uid = StpUtil.getLoginIdAsLong();
            interested = eventService.isInterested(id, uid);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("event", EventView.from(event, interestCount));
        body.put("interested", interested);
        return ApiResponse.ok(body);
    }
}
