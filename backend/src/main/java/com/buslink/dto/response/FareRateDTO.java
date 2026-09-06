package com.buslink.dto.response;

import java.math.BigDecimal;

public record FareRateDTO(
        String originStop,
        String destinationStop,
        Integer stagesCrossed,
        BigDecimal adultFare,
        BigDecimal childFare,
        BigDecimal infantFare) {}
