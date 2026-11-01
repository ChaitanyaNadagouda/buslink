package com.buslink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "otp")
public record OtpProperties(int ttlMinutes, int maxAttempts, int resendCooldownSeconds, int maxSendsPerHour) {}
