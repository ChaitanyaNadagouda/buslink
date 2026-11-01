package com.buslink.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OtpPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "otp.ttl-minutes=5",
                    "otp.max-attempts=5",
                    "otp.resend-cooldown-seconds=60",
                    "otp.max-sends-per-hour=5");

    @Test
    void properties_bindFromKebabCaseProperties() {
        contextRunner.run(context -> {
            OtpProperties properties = context.getBean(OtpProperties.class);
            assertThat(properties.ttlMinutes()).isEqualTo(5);
            assertThat(properties.maxAttempts()).isEqualTo(5);
            assertThat(properties.resendCooldownSeconds()).isEqualTo(60);
            assertThat(properties.maxSendsPerHour()).isEqualTo(5);
        });
    }

    @Configuration
    @ConfigurationPropertiesScan
    static class TestConfig {}
}
