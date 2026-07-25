package com.buslink.controller;

import com.buslink.dto.request.ConductorLoginRequestDTO;
import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.ConductorAuthResponseDTO;
import com.buslink.dto.response.ConductorResponseDTO;
import com.buslink.security.ConductorPrincipal;
import com.buslink.service.ConductorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/conductor")
@RequiredArgsConstructor
public class ConductorController {

    private final ConductorService conductorService;

    @PostMapping("/auth/login")
    public ApiResponse<ConductorAuthResponseDTO> login(@Valid @RequestBody ConductorLoginRequestDTO request) {
        return ApiResponse.success(conductorService.login(request));
    }

    @GetMapping("/profile")
    public ApiResponse<ConductorResponseDTO> getProfile(@AuthenticationPrincipal ConductorPrincipal principal) {
        return ApiResponse.success(
                conductorService.getConductorProfile(principal.getConductor().getConductorId()));
    }
}
