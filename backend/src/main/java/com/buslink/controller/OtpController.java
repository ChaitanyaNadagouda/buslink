package com.buslink.controller;

import com.buslink.dto.request.OtpSendRequestDTO;
import com.buslink.dto.response.ApiResponse;
import com.buslink.service.OtpService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** OTP endpoints. Signup OTP today; home for future OTP flows (e.g. forgot password). */
@RestController
@RequestMapping("/auth/otp")
@RequiredArgsConstructor
public class OtpController {

    private final OtpService otpService;

    @PostMapping("/send")
    public ApiResponse<Void> sendOtp(@Valid @RequestBody OtpSendRequestDTO request) {
        otpService.sendOtp(request.mobileNo());
        return ApiResponse.success("OTP sent", null);
    }
}
