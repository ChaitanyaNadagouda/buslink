package com.buslink.dto.response;

import com.buslink.enums.TransactionStatus;
import com.buslink.enums.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponseDTO(
        UUID transactionId,
        BigDecimal amount,
        TransactionType type,
        TransactionStatus status,
        UUID referenceId,
        Instant createdAt) {}
