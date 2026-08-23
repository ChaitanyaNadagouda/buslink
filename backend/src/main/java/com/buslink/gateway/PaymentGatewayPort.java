package com.buslink.gateway;

import java.math.BigDecimal;

/**
 * Domain-shaped contract for talking to a payment gateway. Business logic
 * depends only on this interface, never on a specific gateway's SDK types.
 */
public interface PaymentGatewayPort {

    GatewayOrder createOrder(BigDecimal amount, String currency, String receipt);

    boolean verifyWebhookSignature(String rawPayload, String signatureHeader);

    GatewayWebhookEvent parseWebhookEvent(String rawPayload);

    String getPublicKeyId();
}
