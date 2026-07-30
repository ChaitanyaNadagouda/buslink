package com.buslink.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record IssueTicketRequestDTO(
        @NotBlank String qrToken,
        @NotNull UUID busId,
        @NotNull UUID routeId,
        @NotBlank String originStop,
        @NotBlank String destinationStop,
        @NotNull @Min(1) Integer adults,
        @NotNull @Min(0) Integer children,
        @NotNull @Min(0) Integer infants) {}
