package com.buslink.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserSignUpRequestDTO(
        @NotBlank String name,
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "^[6-9]\\d{9}$", message = "mobileNo must be a valid 10-digit Indian mobile number")
                String mobileNo,
        @NotBlank @Size(min = 8) String password) {}
