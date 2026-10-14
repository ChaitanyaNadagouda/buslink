package com.buslink.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TicketExpiryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues("ticket.expiry.threshold-hours=24", "ticket.expiry.check-interval-ms=3600000");

    @Test
    void properties_bindFromKebabCaseProperties() {
        contextRunner.run(context -> {
            TicketExpiryProperties properties = context.getBean(TicketExpiryProperties.class);
            assertThat(properties.thresholdHours()).isEqualTo(24L);
            assertThat(properties.checkIntervalMs()).isEqualTo(3600000L);
        });
    }

    @Configuration
    @ConfigurationPropertiesScan
    static class TestConfig {}
}
