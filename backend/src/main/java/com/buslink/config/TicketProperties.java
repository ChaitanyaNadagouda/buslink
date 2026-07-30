package com.buslink.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ticket.idempotency")
public record TicketProperties(long ttlHours) {}
