package com.buslink.dto.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record WalletPaymentRequestDTO(@NotNull UUID ticketId) {}
