package com.buslink.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record OtpSendRequestDTO(
        @NotBlank @Pattern(regexp = "^[6-9]\\d{9}$", message = "mobileNo must be a valid 10-digit Indian mobile number")
                String mobileNo) {}
