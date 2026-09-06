package com.buslink.dto.response;

import java.util.UUID;

public record AdminAuthResponseDTO(
        String accessToken, String refreshToken, UUID adminId, String name, String email) {}
