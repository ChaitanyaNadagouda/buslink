package com.buslink.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;

public record CreateRouteRequestDTO(
        @NotBlank String routeNumber,
        @NotBlank String routeName,
        @NotNull @DecimalMin("0.1") BigDecimal farePerStage,
        @NotEmpty @Valid List<CreateRouteStopDTO> stops) {}
