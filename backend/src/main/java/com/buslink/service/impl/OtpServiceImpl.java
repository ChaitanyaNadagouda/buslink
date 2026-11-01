package com.buslink.service.impl;

import com.buslink.config.OtpProperties;
import com.buslink.exception.ConflictException;
import com.buslink.exception.TooManyRequestsException;
import com.buslink.exception.ValidationException;
import com.buslink.gateway.SmsProvider;
import com.buslink.repository.UserRepository;
import com.buslink.service.OtpService;
import java.security.SecureRandom;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis-backed OTPs (Sprint 8, D4-D8). Keys per mobile number:
 * code (TTL), wrong-attempt counter, resend cooldown, hourly send counter.
 */
@Service
@RequiredArgsConstructor
public class OtpServiceImpl implements OtpService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Duration SEND_WINDOW = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final SmsProvider smsProvider;
    private final UserRepository userRepository;
    private final OtpProperties otpProperties;

    @Override
    public void sendOtp(String mobileNo) {
        if (userRepository.existsByMobileNo(mobileNo)) {
            throw new ConflictException("Mobile number already registered");
        }

        Boolean cooldownSet = redis.opsForValue()
                .setIfAbsent(cooldownKey(mobileNo), "1", Duration.ofSeconds(otpProperties.resendCooldownSeconds()));
        if (!Boolean.TRUE.equals(cooldownSet)) {
            throw new TooManyRequestsException("Please wait before requesting another OTP");
        }

        Long sends = redis.opsForValue().increment(sendsKey(mobileNo));
        if (sends != null && sends == 1) {
            redis.expire(sendsKey(mobileNo), SEND_WINDOW);
        }
        if (sends != null && sends > otpProperties.maxSendsPerHour()) {
            throw new TooManyRequestsException("Too many OTP requests. Try again later");
        }

        String otp = generateOtp();
        redis.opsForValue().set(codeKey(mobileNo), otp, otpTtl());
        redis.delete(attemptsKey(mobileNo));
        smsProvider.sendOtp(mobileNo, otp);
    }

    @Override
    public void verifyOtp(String mobileNo, String otp) {
        String stored = redis.opsForValue().get(codeKey(mobileNo));
        if (stored == null) {
            throw new ValidationException("OTP expired or not requested");
        }
        if (stored.equals(otp)) {
            return;
        }

        Long attempts = redis.opsForValue().increment(attemptsKey(mobileNo));
        if (attempts != null && attempts == 1) {
            redis.expire(attemptsKey(mobileNo), otpTtl());
        }
        if (attempts != null && attempts >= otpProperties.maxAttempts()) {
            clearOtp(mobileNo);
            throw new ValidationException("Too many wrong attempts. Request a new OTP");
        }
        throw new ValidationException("Invalid OTP");
    }

    @Override
    public void clearOtp(String mobileNo) {
        redis.delete(codeKey(mobileNo));
        redis.delete(attemptsKey(mobileNo));
    }

    private String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private Duration otpTtl() {
        return Duration.ofMinutes(otpProperties.ttlMinutes());
    }

    private static String codeKey(String mobileNo) {
        return "otp:code:" + mobileNo;
    }

    private static String attemptsKey(String mobileNo) {
        return "otp:attempts:" + mobileNo;
    }

    private static String cooldownKey(String mobileNo) {
        return "otp:cooldown:" + mobileNo;
    }

    private static String sendsKey(String mobileNo) {
        return "otp:sends:" + mobileNo;
    }
}
