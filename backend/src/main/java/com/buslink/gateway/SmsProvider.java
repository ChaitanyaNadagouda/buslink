package com.buslink.gateway;

/**
 * Port for sending SMS. Business logic depends only on this; the active
 * Spring profile picks the adapter (dev = mock, prod = MSG91).
 */
public interface SmsProvider {

    void sendOtp(String mobileNo, String otp);
}
