package com.buslink.controller;

import com.buslink.dto.response.ApiResponse;
import com.buslink.dto.response.UserProfileResponseDTO;
import com.buslink.security.UserPrincipal;
import com.buslink.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    public ApiResponse<UserProfileResponseDTO> getProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(userService.getUserProfile(principal.getUser().getUserId()));
    }

    @GetMapping("/qr")
    public ApiResponse<String> getQrToken(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(userService.getQrToken(principal.getUser().getUserId()));
    }
}
