package com.buslink.dto.response;

import com.buslink.enums.PaymentStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record RechargeConfirmResponseDTO(
        UUID paymentId,
        BigDecimal amountCredited,
        BigDecimal walletBalanceAfter,
        BigDecimal overdraftRecovered,
        PaymentStatus status) {}
