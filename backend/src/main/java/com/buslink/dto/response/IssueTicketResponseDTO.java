package com.buslink.dto.response;

import com.buslink.enums.TicketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record IssueTicketResponseDTO(
        UUID ticketId,
        UUID userId,
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
        Instant issuedAt) {}
