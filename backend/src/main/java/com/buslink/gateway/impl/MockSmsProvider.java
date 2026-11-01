package com.buslink.gateway.impl;

import com.buslink.gateway.SmsProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("dev")
public class MockSmsProvider implements SmsProvider {

    // Loud on purpose: if production ever runs without SPRING_PROFILES_ACTIVE=prod, OTPs land in logs.
    @PostConstruct
    void warnIfMockActive() {
        log.warn("MockSmsProvider is active: OTPs are logged, not sent. Never run production on the dev profile.");
    }

    @Override
    public void sendOtp(String mobileNo, String otp) {
        log.info("[MOCK SMS] OTP for {}: {}", mobileNo, otp);
    }
}
