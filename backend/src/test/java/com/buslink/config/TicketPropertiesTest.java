package com.buslink.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class TicketPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues("ticket.idempotency.ttl-hours=24");

    @Test
    void ttlHours_bindsFromKebabCaseProperty() {
        contextRunner.run(context -> {
            TicketProperties properties = context.getBean(TicketProperties.class);
            assertThat(properties.ttlHours()).isEqualTo(24L);
        });
    }

    @Configuration
    @ConfigurationPropertiesScan
    static class TestConfig {}
}
