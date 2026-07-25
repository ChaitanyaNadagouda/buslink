package com.buslink.dto.response;

import com.buslink.enums.RouteStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record RouteResponseDTO(
        UUID routeId,
        String routeNumber,
        String routeName,
        String originStop,
        String destinationStop,
        Integer totalStops,
        BigDecimal farePerStage,
        RouteStatus status) {}
