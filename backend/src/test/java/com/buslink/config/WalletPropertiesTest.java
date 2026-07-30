package com.buslink.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class WalletPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues("wallet.overdraft-limit=100.00");

    @Test
    void overdraftLimit_bindsFromKebabCaseProperty() {
        contextRunner.run(context -> {
            WalletProperties properties = context.getBean(WalletProperties.class);
            assertThat(properties.overdraftLimit()).isEqualByComparingTo("100.00");
        });
    }

    @Configuration
    @ConfigurationPropertiesScan
    static class TestConfig {}
}
