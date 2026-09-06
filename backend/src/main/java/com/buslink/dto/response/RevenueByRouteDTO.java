package com.buslink.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record RevenueByRouteDTO(UUID routeId, String routeName, BigDecimal totalRevenue) {}
