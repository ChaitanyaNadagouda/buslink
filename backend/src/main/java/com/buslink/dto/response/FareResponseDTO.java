package com.buslink.dto.response;

import java.math.BigDecimal;

public record FareResponseDTO(
        String originStop,
        String destinationStop,
        Integer stagesCrossed,
        BigDecimal adultFare,
        BigDecimal childFare,
        BigDecimal infantFare,
        BigDecimal totalFare) {}
