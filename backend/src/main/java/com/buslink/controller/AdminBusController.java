package com.buslink.controller;

import com.buslink.dto.request.AssignConductorRequestDTO;
import com.buslink.dto.request.CreateBusRequestDTO;
import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.BusResponseDTO;
import com.buslink.dto.response.ConductorResponseDTO;
import com.buslink.service.BusService;
import com.buslink.service.ConductorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AdminBusController {

    private final BusService busService;
    private final ConductorService conductorService;

    @PostMapping("/admin/buses")
    public ApiResponse<BusResponseDTO> createBus(@Valid @RequestBody CreateBusRequestDTO request) {
        return ApiResponse.success(busService.createBus(request));
    }

    @GetMapping("/admin/buses")
    public ApiResponse<List<BusResponseDTO>> getAllBuses() {
        return ApiResponse.success(busService.getAllBuses());
    }

    @GetMapping("/admin/buses/{busId}")
    public ApiResponse<BusResponseDTO> getBusByBusId(@PathVariable UUID busId) {
        return ApiResponse.success(busService.getBusByBusId(busId));
    }

    @PutMapping("/admin/conductors/{conductorId}/assign-bus")
    public ApiResponse<ConductorResponseDTO> assignBus(
            @PathVariable UUID conductorId, @Valid @RequestBody AssignConductorRequestDTO request) {
        return ApiResponse.success(conductorService.assignBus(conductorId, request.busId()));
    }
}
