package com.buslink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sync")
public record SyncProperties(
        long retryIntervalMs, int maxSyncAgeDays, int clockSkewToleranceMinutes, int maxRetryAttempts) {}
