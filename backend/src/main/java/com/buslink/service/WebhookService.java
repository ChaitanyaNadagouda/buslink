package com.buslink.service;

public interface WebhookService {

    void handleRazorpayWebhook(String rawPayload, String signatureHeader);
}
