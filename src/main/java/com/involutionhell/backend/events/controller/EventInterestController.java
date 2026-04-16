package com.involutionhell.backend.events.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.involutionhell.backend.common.api.ApiResponse;
import com.involutionhell.backend.events.service.EventService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 活动"感兴趣"开关（登录用户）。
 *
 * 路由：
 * - POST   /api/events/{id}/interest    标记感兴趣（幂等）
 * - DELETE /api/events/{id}/interest    取消感兴趣（幂等）
 *
 * 返回结构统一包含 count + interested，前端点完按钮可以直接用返回值刷新 UI，
 * 不用再额外调一次 /api/events/{id} 详情接口。
 */
@RestController
@RequestMapping("/api/events")
public class EventInterestController {

    private final EventService eventService;

    public EventInterestController(EventService eventService) {
        this.eventService = eventService;
    }

    @SaCheckLogin
    @PostMapping("/{id}/interest")
    public ApiResponse<Map<String, Object>> mark(@PathVariable Long id) {
        long uid = StpUtil.getLoginIdAsLong();
        if (eventService.findById(id).isEmpty()) {
            return new ApiResponse<>(false, "活动不存在", null);
        }
        eventService.markInterested(id, uid);
        return ApiResponse.ok(Map.of(
                "count", eventService.countInterest(id),
                "interested", true
        ));
    }

    @SaCheckLogin
    @DeleteMapping("/{id}/interest")
    public ApiResponse<Map<String, Object>> unmark(@PathVariable Long id) {
        long uid = StpUtil.getLoginIdAsLong();
        if (eventService.findById(id).isEmpty()) {
            return new ApiResponse<>(false, "活动不存在", null);
        }
        eventService.unmarkInterested(id, uid);
        return ApiResponse.ok(Map.of(
                "count", eventService.countInterest(id),
                "interested", false
        ));
    }
}
