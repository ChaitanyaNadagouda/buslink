package com.buslink.gateway.impl;

import com.buslink.config.RazorpayProperties;
import com.buslink.exception.PaymentGatewayException;
import com.buslink.gateway.GatewayEventType;
import com.buslink.gateway.GatewayOrder;
import com.buslink.gateway.GatewayWebhookEvent;
import com.buslink.gateway.PaymentGatewayPort;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

/**
 * Only class in the codebase that references Razorpay SDK types directly.
 * Everything else depends on {@link PaymentGatewayPort}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RazorpayGatewayAdapter implements PaymentGatewayPort {

    private final RazorpayClient razorpayClient;
    private final RazorpayProperties razorpayProperties;

    @Override
    public GatewayOrder createOrder(BigDecimal amount, String currency, String receipt) {
        JSONObject orderRequest = new JSONObject();
        orderRequest.put("amount", amount.multiply(BigDecimal.valueOf(100)).intValue());
        orderRequest.put("currency", currency);
        orderRequest.put("receipt", receipt);

        try {
            Order order = razorpayClient.orders.create(orderRequest);
            String razorpayOrderId = order.get("id");
            return new GatewayOrder(razorpayOrderId);
        } catch (RazorpayException e) {
            throw new PaymentGatewayException("Failed to create Razorpay order", e);
        }
    }

    @Override
    public boolean verifyWebhookSignature(String rawPayload, String signatureHeader) {
        try {
            return Utils.verifyWebhookSignature(rawPayload, signatureHeader, razorpayProperties.webhookSecret());
        } catch (RazorpayException e) {
            log.warn("Webhook signature verification failed to run, treating as invalid", e);
            return false;
        }
    }

    @Override
    public GatewayWebhookEvent parseWebhookEvent(String rawPayload) {
        JSONObject payload = new JSONObject(rawPayload);
        String event = payload.getString("event");

        GatewayEventType type =
                switch (event) {
                    case "payment.captured" -> GatewayEventType.SUCCESS;
                    case "payment.failed" -> GatewayEventType.FAILED;
                    default -> throw new PaymentGatewayException("Unrecognized webhook event type: " + event);
                };

        String razorpayOrderId = payload
                .getJSONObject("payload")
                .getJSONObject("payment")
                .getJSONObject("entity")
                .getString("order_id");

        return new GatewayWebhookEvent(type, razorpayOrderId);
    }

    @Override
    public String getPublicKeyId() {
        return razorpayProperties.keyId();
    }
}
