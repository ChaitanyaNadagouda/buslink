package com.buslink.controller;

import com.buslink.dto.request.AdminLoginRequestDTO;
import com.buslink.dto.response.AdminAuthResponseDTO;
import com.buslink.dto.response.ApiResponse;
import com.buslink.service.AdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminService adminService;

    @PostMapping("/login")
    public ApiResponse<AdminAuthResponseDTO> login(@Valid @RequestBody AdminLoginRequestDTO request) {
        return ApiResponse.success(adminService.login(request));
    }
}
