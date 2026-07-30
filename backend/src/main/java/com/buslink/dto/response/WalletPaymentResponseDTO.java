package com.buslink.dto.response;

import com.buslink.enums.TicketStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletPaymentResponseDTO(
        UUID ticketId,
        BigDecimal amountDeducted,
        BigDecimal walletBalanceAfter,
        TicketStatus ticketStatus,
        Instant paidAt) {}
