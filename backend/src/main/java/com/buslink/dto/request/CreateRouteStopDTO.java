package com.buslink.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateRouteStopDTO(
        @NotBlank String stopName,
        @NotNull @Min(1) Integer stopSequence,
        @NotNull @Min(1) Integer stageNumber) {}
