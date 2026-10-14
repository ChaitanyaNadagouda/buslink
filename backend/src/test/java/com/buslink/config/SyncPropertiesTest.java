package com.buslink.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SyncPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "sync.retry-interval-ms=300000",
                    "sync.max-sync-age-days=7",
                    "sync.clock-skew-tolerance-minutes=5",
                    "sync.max-retry-attempts=10");

    @Test
    void properties_bindFromKebabCaseProperties() {
        contextRunner.run(context -> {
            SyncProperties properties = context.getBean(SyncProperties.class);
            assertThat(properties.retryIntervalMs()).isEqualTo(300000L);
            assertThat(properties.maxSyncAgeDays()).isEqualTo(7);
            assertThat(properties.clockSkewToleranceMinutes()).isEqualTo(5);
            assertThat(properties.maxRetryAttempts()).isEqualTo(10);
        });
    }

    @Configuration
    @ConfigurationPropertiesScan
    static class TestConfig {}
}
