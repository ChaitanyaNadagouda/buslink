package com.buslink.dto.response;

import java.util.UUID;

public record ConductorAuthResponseDTO(
        String accessToken,
        String refreshToken,
        UUID conductorId,
        String name,
        String email,
        UUID busId,
        UUID routeId) {}
