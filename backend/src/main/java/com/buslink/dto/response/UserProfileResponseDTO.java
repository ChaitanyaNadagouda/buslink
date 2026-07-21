package com.buslink.dto.response;

import com.buslink.enums.UserStatus;
import java.time.Instant;
import java.util.UUID;

public record UserProfileResponseDTO(
        UUID userId,
        String name,
        String email,
        String mobileNo,
        UserStatus status,
        String qrToken,
        Instant createdAt) {}
