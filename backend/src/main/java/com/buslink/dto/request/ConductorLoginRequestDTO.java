package com.buslink.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record ConductorLoginRequestDTO(@NotBlank @Email String email, @NotBlank String password) {}
