package com.buslink.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record RechargeInitiateResponseDTO(
        UUID paymentId, String razorpayOrderId, BigDecimal amount, String currency, String razorpayKeyId) {}
