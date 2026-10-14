package com.buslink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticket.expiry")
public record TicketExpiryProperties(long thresholdHours, long checkIntervalMs) {}
