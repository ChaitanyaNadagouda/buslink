package com.buslink.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record RechargeInitiateRequestDTO(@NotNull @DecimalMin("1.00") BigDecimal amount) {}
