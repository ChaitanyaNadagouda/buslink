package com.buslink.controller;

import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.ConductorActivityDTO;
import com.buslink.dto.response.RevenueByRouteDTO;
import com.buslink.dto.response.TicketsPerDayDTO;
import com.buslink.dto.response.TopRouteDTO;
import com.buslink.security.AdminPrincipal;
import com.buslink.service.AnalyticsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/revenue-by-route")
    public ApiResponse<List<RevenueByRouteDTO>> getRevenueByRoute(@AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.success(analyticsService.getRevenueByRoute());
    }

    @GetMapping("/tickets-per-day")
    public ApiResponse<List<TicketsPerDayDTO>> getTicketsPerDay(@AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.success(analyticsService.getTicketsPerDay());
    }

    @GetMapping("/top-routes")
    public ApiResponse<List<TopRouteDTO>> getTopRoutes(@AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.success(analyticsService.getTopRoutes());
    }

    @GetMapping("/conductor-activity")
    public ApiResponse<List<ConductorActivityDTO>> getConductorActivity(
            @AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.success(analyticsService.getConductorActivity());
    }
}
