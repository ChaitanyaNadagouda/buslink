package com.buslink.gateway.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.buslink.config.RazorpayProperties;
import com.buslink.exception.PaymentGatewayException;
import com.buslink.gateway.GatewayEventType;
import com.buslink.gateway.GatewayOrder;
import com.buslink.gateway.GatewayWebhookEvent;
import com.razorpay.Order;
import com.razorpay.OrderClient;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RazorpayGatewayAdapterTest {

    @Mock
    private OrderClient orderClient;

    private RazorpayProperties razorpayProperties;
    private RazorpayGatewayAdapter adapter;

    @BeforeEach
    void setUp() throws RazorpayException {
        razorpayProperties = new RazorpayProperties("rzp_test_key123", "keySecret123", "webhookSecret123");
        RazorpayClient razorpayClient =
                new RazorpayClient(razorpayProperties.keyId(), razorpayProperties.keySecret());
        razorpayClient.orders = orderClient;
        adapter = new RazorpayGatewayAdapter(razorpayClient, razorpayProperties);
    }

    @Test
    void createOrder_success_returnsGatewayOrder() throws RazorpayException {
        Order order = new Order(new JSONObject().put("id", "order_abc123"));
        when(orderClient.create(any(JSONObject.class))).thenReturn(order);

        GatewayOrder result = adapter.createOrder(BigDecimal.valueOf(200), "INR", "receipt-1");

        assertThat(result.gatewayOrderId()).isEqualTo("order_abc123");
    }

    @Test
    void createOrder_razorpayExceptionThrown_throwsPaymentGatewayException() throws RazorpayException {
        when(orderClient.create(any(JSONObject.class))).thenThrow(new RazorpayException("upstream failure"));

        assertThatThrownBy(() -> adapter.createOrder(BigDecimal.valueOf(200), "INR", "receipt-1"))
                .isInstanceOf(PaymentGatewayException.class);
    }

    @Test
    void verifyWebhookSignature_validSignature_returnsTrue() throws Exception {
        String payload = "{\"event\":\"payment.captured\"}";
        String signature = hmacSha256Hex(payload, razorpayProperties.webhookSecret());

        assertThat(adapter.verifyWebhookSignature(payload, signature)).isTrue();
    }

    @Test
    void verifyWebhookSignature_invalidSignature_returnsFalse() {
        assertThat(adapter.verifyWebhookSignature("{\"event\":\"payment.captured\"}", "not-a-real-signature"))
                .isFalse();
    }

    @Test
    void verifyWebhookSignature_razorpayExceptionThrown_returnsFalse() {
        try (MockedStatic<Utils> utils = mockStatic(Utils.class)) {
            utils.when(() -> Utils.verifyWebhookSignature(any(), any(), any()))
                    .thenThrow(new RazorpayException("crypto failure"));

            assertThat(adapter.verifyWebhookSignature("{}", "sig")).isFalse();
        }
    }

    @Test
    void parseWebhookEvent_paymentCaptured_returnsSuccess() {
        String payload =
                """
                {"event":"payment.captured","payload":{"payment":{"entity":{"order_id":"order_abc123"}}}}
                """;

        GatewayWebhookEvent event = adapter.parseWebhookEvent(payload);

        assertThat(event.type()).isEqualTo(GatewayEventType.SUCCESS);
        assertThat(event.gatewayOrderId()).isEqualTo("order_abc123");
    }

    @Test
    void parseWebhookEvent_paymentFailed_returnsFailed() {
        String payload =
                """
                {"event":"payment.failed","payload":{"payment":{"entity":{"order_id":"order_xyz789"}}}}
                """;

        GatewayWebhookEvent event = adapter.parseWebhookEvent(payload);

        assertThat(event.type()).isEqualTo(GatewayEventType.FAILED);
        assertThat(event.gatewayOrderId()).isEqualTo("order_xyz789");
    }

    @Test
    void getPublicKeyId_returnsConfiguredKeyId() {
        assertThat(adapter.getPublicKeyId()).isEqualTo("rzp_test_key123");
    }

    private static String hmacSha256Hex(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
