package com.buslink.config;

import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RazorpayConfig {

    @Bean
    public RazorpayClient razorpayClient(RazorpayProperties properties) throws RazorpayException {
        return new RazorpayClient(properties.keyId(), properties.keySecret());
    }
}
