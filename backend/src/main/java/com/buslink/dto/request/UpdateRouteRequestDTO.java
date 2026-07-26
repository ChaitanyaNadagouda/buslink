package com.buslink.dto.request;

import com.buslink.enums.RouteStatus;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public record UpdateRouteRequestDTO(
        @DecimalMin("0.1") BigDecimal farePerStage, RouteStatus status) {}
