package com.buslink.controller;

import com.buslink.dto.request.CreateRouteRequestDTO;
import com.buslink.dto.request.CreateRouteStopDTO;
import com.buslink.dto.request.UpdateRouteRequestDTO;
import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.RouteResponseDTO;
import com.buslink.dto.response.RouteStopResponseDTO;
import com.buslink.service.FareService;
import com.buslink.service.RouteService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/routes")
@RequiredArgsConstructor
public class AdminRouteController {

    private final RouteService routeService;
    private final FareService fareService;

    @PostMapping
    public ApiResponse<RouteResponseDTO> createRoute(@Valid @RequestBody CreateRouteRequestDTO request) {
        return ApiResponse.success(routeService.createRoute(request));
    }

    @GetMapping
    public ApiResponse<List<RouteResponseDTO>> getAllRoutes() {
        return ApiResponse.success(routeService.getAllRoutes());
    }

    @GetMapping("/{routeId}")
    public ApiResponse<RouteResponseDTO> getRouteById(@PathVariable UUID routeId) {
        return ApiResponse.success(routeService.getRouteById(routeId));
    }

    @PutMapping("/{routeId}/status")
    public ApiResponse<RouteResponseDTO> updateRoute(
            @PathVariable UUID routeId, @Valid @RequestBody UpdateRouteRequestDTO request) {
        return ApiResponse.success(routeService.updateRoute(routeId, request));
    }

    @PostMapping("/{routeId}/stops")
    public ApiResponse<RouteStopResponseDTO> addStop(
            @PathVariable UUID routeId, @Valid @RequestBody CreateRouteStopDTO request) {
        return ApiResponse.success(routeService.addStop(routeId, request));
    }

    @GetMapping("/{routeId}/stops")
    public ApiResponse<List<RouteStopResponseDTO>> getStopsForRoute(@PathVariable UUID routeId) {
        return ApiResponse.success(fareService.getStopsForRoute(routeId));
    }
}
