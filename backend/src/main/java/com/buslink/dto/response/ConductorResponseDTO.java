package com.buslink.dto.response;

import com.buslink.enums.ConductorStatus;
import java.util.UUID;

public record ConductorResponseDTO(
        UUID conductorId, String name, String email, UUID busId, ConductorStatus status) {}
