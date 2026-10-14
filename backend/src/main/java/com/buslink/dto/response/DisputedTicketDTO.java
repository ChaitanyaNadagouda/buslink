package com.buslink.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DisputedTicketDTO(
        UUID ticketId,
        String passengerName,
        String conductorName,
        String routeName,
        String originStop,
        String destinationStop,
        BigDecimal totalFare,
        Instant issuedAt) {}
