package com.buslink.dto.response;

import com.buslink.enums.TicketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TicketDetailResponseDTO(
        UUID ticketId,
        String conductorName,
        String busNumber,
        String routeNumber,
        String originStop,
        String destinationStop,
        Integer stagesCrossed,
        Integer adults,
        Integer children,
        Integer infants,
        BigDecimal adultFare,
        BigDecimal childFare,
        BigDecimal totalFare,
        TicketStatus status,
        Instant issuedAt,
        Instant paidAt) {}
