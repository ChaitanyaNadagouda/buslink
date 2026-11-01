package com.buslink.service;

public interface OtpService {

    /** Generates and sends an OTP, subject to the per-number send limits. */
    void sendOtp(String mobileNo);

    /** Throws if the OTP is missing, expired or wrong; counts wrong attempts. Does not consume the OTP. */
    void verifyOtp(String mobileNo, String otp);

    /** Removes the OTP after it has been used (successful registration). */
    void clearOtp(String mobileNo);
}
