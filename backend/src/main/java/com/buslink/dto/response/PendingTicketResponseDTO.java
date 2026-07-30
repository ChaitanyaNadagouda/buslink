package com.buslink.dto.response;

import com.buslink.enums.TicketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PendingTicketResponseDTO(
        UUID ticketId,
        String passengerName,
        String passengerQrToken,
        String originStop,
        String destinationStop,
        BigDecimal totalFare,
        TicketStatus status,
        Instant issuedAt,
        Long minutesSinceIssue) {}
