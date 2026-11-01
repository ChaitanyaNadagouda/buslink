package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buslink.config.OtpProperties;
import com.buslink.exception.ConflictException;
import com.buslink.exception.TooManyRequestsException;
import com.buslink.exception.ValidationException;
import com.buslink.gateway.SmsProvider;
import com.buslink.repository.UserRepository;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class OtpServiceImplTest {

    private static final String MOBILE = "9876543210";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private SmsProvider smsProvider;

    @Mock
    private UserRepository userRepository;

    private OtpServiceImpl otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpServiceImpl(redis, smsProvider, userRepository, new OtpProperties(5, 5, 60, 5));
    }

    @Test
    void sendOtp_success_storesSixDigitCodeWithTtlAndSendsSameCode() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent("otp:cooldown:" + MOBILE, "1", Duration.ofSeconds(60))).thenReturn(true);
        when(valueOps.increment("otp:sends:" + MOBILE)).thenReturn(1L);

        otpService.sendOtp(MOBILE);

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("otp:code:" + MOBILE), code.capture(), eq(Duration.ofMinutes(5)));
        assertThat(code.getValue()).matches("\\d{6}");
        verify(smsProvider).sendOtp(MOBILE, code.getValue());
        verify(redis).expire("otp:sends:" + MOBILE, Duration.ofHours(1));
        verify(redis).delete("otp:attempts:" + MOBILE);
    }

    @Test
    void sendOtp_registeredMobile_409AndNoSms() {
        when(userRepository.existsByMobileNo(MOBILE)).thenReturn(true);

        assertThatThrownBy(() -> otpService.sendOtp(MOBILE))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Mobile number already registered");
        verify(smsProvider, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void sendOtp_withinCooldown_429AndNoSms() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent("otp:cooldown:" + MOBILE, "1", Duration.ofSeconds(60))).thenReturn(false);

        assertThatThrownBy(() -> otpService.sendOtp(MOBILE)).isInstanceOf(TooManyRequestsException.class);
        verify(smsProvider, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void sendOtp_hourlyCapExceeded_429AndNoSms() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent("otp:cooldown:" + MOBILE, "1", Duration.ofSeconds(60))).thenReturn(true);
        when(valueOps.increment("otp:sends:" + MOBILE)).thenReturn(6L);

        assertThatThrownBy(() -> otpService.sendOtp(MOBILE))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many OTP requests. Try again later");
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
        verify(smsProvider, never()).sendOtp(anyString(), anyString());
    }

    @Test
    void verifyOtp_correctCode_passesAndKeepsOtp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("otp:code:" + MOBILE)).thenReturn("482913");

        assertThatCode(() -> otpService.verifyOtp(MOBILE, "482913")).doesNotThrowAnyException();
        verify(redis, never()).delete(anyString());
    }

    @Test
    void verifyOtp_expiredOrNeverSent_400() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("otp:code:" + MOBILE)).thenReturn(null);

        assertThatThrownBy(() -> otpService.verifyOtp(MOBILE, "482913"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("OTP expired or not requested");
    }

    @Test
    void verifyOtp_wrongCode_countsAttemptAndKeepsOtp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("otp:code:" + MOBILE)).thenReturn("482913");
        when(valueOps.increment("otp:attempts:" + MOBILE)).thenReturn(1L);

        assertThatThrownBy(() -> otpService.verifyOtp(MOBILE, "000000"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid OTP");
        verify(redis).expire("otp:attempts:" + MOBILE, Duration.ofMinutes(5));
        verify(redis, never()).delete("otp:code:" + MOBILE);
    }

    @Test
    void verifyOtp_fifthWrongCode_deletesOtp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("otp:code:" + MOBILE)).thenReturn("482913");
        when(valueOps.increment("otp:attempts:" + MOBILE)).thenReturn(5L);

        assertThatThrownBy(() -> otpService.verifyOtp(MOBILE, "000000"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Too many wrong attempts. Request a new OTP");
        verify(redis).delete("otp:code:" + MOBILE);
        verify(redis).delete("otp:attempts:" + MOBILE);
    }
}
