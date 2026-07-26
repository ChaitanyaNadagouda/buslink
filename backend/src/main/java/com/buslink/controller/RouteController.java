package com.buslink.controller;

import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.FareResponseDTO;
import com.buslink.dto.response.RouteStopResponseDTO;
import com.buslink.service.FareService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/routes")
@RequiredArgsConstructor
public class RouteController {

    private final FareService fareService;

    @GetMapping("/{routeId}/stops")
    public ApiResponse<List<RouteStopResponseDTO>> getStops(
            @PathVariable UUID routeId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String after) {
        List<RouteStopResponseDTO> stops;
        if (after != null) {
            stops = fareService.searchStopsAfter(routeId, after, search != null ? search : "");
        } else if (search != null) {
            stops = fareService.searchStops(routeId, search);
        } else {
            stops = fareService.getStopsForRoute(routeId);
        }
        return ApiResponse.success(stops);
    }

    @GetMapping("/{routeId}/fare")
    public ApiResponse<FareResponseDTO> calculateFare(
            @PathVariable UUID routeId,
            @RequestParam String origin,
            @RequestParam String destination,
            @RequestParam int adults,
            @RequestParam int children,
            @RequestParam int infants) {
        return ApiResponse.success(
                fareService.calculateFare(routeId, origin, destination, adults, children, infants));
    }
}
