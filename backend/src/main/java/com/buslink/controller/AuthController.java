package com.buslink.controller;

import com.buslink.dto.request.RefreshTokenRequestDTO;
import com.buslink.dto.request.UserLoginRequestDTO;
import com.buslink.dto.request.UserSignUpRequestDTO;
import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.AuthResponseDTO;
import com.buslink.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ApiResponse<AuthResponseDTO> register(@Valid @RequestBody UserSignUpRequestDTO request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponseDTO> login(@Valid @RequestBody UserLoginRequestDTO request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponseDTO> refresh(@Valid @RequestBody RefreshTokenRequestDTO request) {
        return ApiResponse.success(authService.refreshToken(request));
    }
}
