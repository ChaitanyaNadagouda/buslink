package com.buslink.dto.response;

import java.util.UUID;

public record AuthResponseDTO(
        String accessToken, String refreshToken, UUID userId, String email, String name) {}
