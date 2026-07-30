package com.buslink.dto.response;

import com.buslink.enums.WalletStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record WalletBalanceResponseDTO(
        BigDecimal balance,
        WalletStatus status,
        Instant lastUpdated) {}
