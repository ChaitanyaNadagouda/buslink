package com.buslink.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class
RazorpayPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "razorpay.key-id=rzp_test_abc123",
                    "razorpay.key-secret=secret123",
                    "razorpay.webhook-secret=webhooksecret123");

    @Test
    void fields_bindFromKebabCaseProperties() {
        contextRunner.run(context -> {
            RazorpayProperties properties = context.getBean(RazorpayProperties.class);
            assertThat(properties.keyId()).isEqualTo("rzp_test_abc123");
            assertThat(properties.keySecret()).isEqualTo("secret123");
            assertThat(properties.webhookSecret()).isEqualTo("webhooksecret123");
        });
    }

    @Configuration
    @ConfigurationPropertiesScan
    static class TestConfig {}
}
