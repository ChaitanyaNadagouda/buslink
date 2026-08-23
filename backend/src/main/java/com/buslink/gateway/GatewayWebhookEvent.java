package com.buslink.gateway;

public record GatewayWebhookEvent(GatewayEventType type, String gatewayOrderId) {}
