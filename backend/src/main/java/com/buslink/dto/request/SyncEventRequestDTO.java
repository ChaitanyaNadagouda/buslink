package com.buslink.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SyncEventRequestDTO(
        @NotNull UUID eventId,
        @NotNull UUID conductorId,
        @NotNull UUID userId,
        @NotBlank String passengerQrToken,
        @NotNull UUID busId,
        @NotNull UUID routeId,
        @NotBlank String originStop,
        @NotBlank String destinationStop,
        @NotNull Instant issuedAt,
        BigDecimal clientFare) {}
