package com.buslink.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record TicketUpiPaymentInitiateRequestDTO(@NotNull UUID ticketId) {}
