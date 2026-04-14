package com.involutionhell.backend.analytics.controller;

import com.involutionhell.backend.analytics.dto.TopDocDto;
import com.involutionhell.backend.analytics.service.AnalyticsService;
import com.involutionhell.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private static final Set<String> VALID_WINDOWS = Set.of("7d", "30d", "all");

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/top-docs")
    public ApiResponse<List<TopDocDto>> topDocs(
            @RequestParam(defaultValue = "30d") String window,
            @RequestParam(defaultValue = "20") int limit
    ) {
        if (!VALID_WINDOWS.contains(window)) {
            window = "30d";
        }
        limit = Math.min(Math.max(limit, 1), 100);

        List<TopDocDto> docs = analyticsService.getTopDocs(window, limit);
        return ApiResponse.ok(docs);
    }
}
