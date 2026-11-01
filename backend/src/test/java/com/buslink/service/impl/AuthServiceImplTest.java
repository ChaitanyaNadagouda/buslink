package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buslink.dto.request.UserLoginRequestDTO;
import com.buslink.dto.request.UserSignUpRequestDTO;
import com.buslink.dto.response.AuthResponseDTO;
import com.buslink.entity.User;
import com.buslink.entity.Wallet;
import com.buslink.enums.UserStatus;
import com.buslink.enums.WalletStatus;
import com.buslink.exception.ConflictException;
import com.buslink.exception.ValidationException;
import com.buslink.repository.UserRepository;
import com.buslink.repository.WalletRepository;
import com.buslink.security.JwtUtil;
import com.buslink.service.OtpService;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtil jwtUtil;

    @Mock
    private OtpService otpService;

    @InjectMocks
    private AuthServiceImpl authService;

    @Test
    void register_success() {
        UserSignUpRequestDTO request =
                new UserSignUpRequestDTO("Rider One", "rider1@buslink.com", "9876543210", "password123", "482913");
        UUID generatedUserId = UUID.randomUUID();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(request.password())).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setUserId(generatedUserId);
            return user;
        });
        when(jwtUtil.generatePassengerQrToken(any(User.class))).thenReturn("signed-qr-jwt");
        when(jwtUtil.generateAccessToken(any(User.class))).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(any(User.class))).thenReturn("refresh-token");

        AuthResponseDTO response = authService.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository, times(2)).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getQrToken()).isEqualTo("signed-qr-jwt");

        ArgumentCaptor<Wallet> walletCaptor = ArgumentCaptor.forClass(Wallet.class);
        verify(walletRepository).save(walletCaptor.capture());
        Wallet savedWallet = walletCaptor.getValue();
        assertThat(savedWallet.getUserId()).isEqualTo(generatedUserId);
        assertThat(savedWallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(savedWallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);

        assertThat(response.userId()).isEqualTo(generatedUserId);
        assertThat(response.email()).isEqualTo(request.email());
        assertThat(response.name()).isEqualTo(request.name());
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");

        InOrder inOrder = inOrder(otpService, userRepository);
        inOrder.verify(otpService).verifyOtp(request.mobileNo(), request.otp());
        inOrder.verify(userRepository, times(2)).save(any(User.class));
        inOrder.verify(otpService).clearOtp(request.mobileNo());
    }

    @Test
    void register_duplicateEmail() {
        UserSignUpRequestDTO request =
                new UserSignUpRequestDTO("Rider One", "rider1@buslink.com", "9876543210", "password123", "482913");
        User existing = User.builder().email(request.email()).build();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.register(request)).isInstanceOf(ConflictException.class);
        verifyNoInteractions(otpService);
    }

    @Test
    void register_duplicateMobileNo() {
        UserSignUpRequestDTO request =
                new UserSignUpRequestDTO("Rider Two", "rider2@buslink.com", "9876543210", "password123", "482913");

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        when(userRepository.existsByMobileNo(request.mobileNo())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Mobile number already registered");
        verify(userRepository, never()).save(any(User.class));
        verifyNoInteractions(otpService);
    }

    @Test
    void register_wrongOtp_400AndNothingSaved() {
        UserSignUpRequestDTO request =
                new UserSignUpRequestDTO("Rider Three", "rider3@buslink.com", "9876543211", "password123", "000000");

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        doThrow(new ValidationException("Invalid OTP")).when(otpService).verifyOtp(request.mobileNo(), request.otp());

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid OTP");
        verify(userRepository, never()).save(any(User.class));
        verify(walletRepository, never()).save(any(Wallet.class));
        verify(otpService, never()).clearOtp(request.mobileNo());
    }

    @Test
    void login_success() {
        UserLoginRequestDTO request = new UserLoginRequestDTO("rider1@buslink.com", "password123");
        User user = User.builder()
                .userId(UUID.randomUUID())
                .name("Rider One")
                .email(request.email())
                .passwordHash("hashed-password")
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), user.getPasswordHash())).thenReturn(true);
        when(jwtUtil.generateAccessToken(user)).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(user)).thenReturn("refresh-token");

        AuthResponseDTO response = authService.login(request);

        assertThat(response.userId()).isEqualTo(user.getUserId());
        assertThat(response.email()).isEqualTo(user.getEmail());
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void login_wrongPassword() {
        UserLoginRequestDTO request = new UserLoginRequestDTO("rider1@buslink.com", "wrong-password");
        User user = User.builder()
                .email(request.email())
                .passwordHash("hashed-password")
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void login_inactiveUser() {
        UserLoginRequestDTO request = new UserLoginRequestDTO("rider1@buslink.com", "password123");
        User user = User.builder()
                .email(request.email())
                .passwordHash("hashed-password")
                .status(UserStatus.INACTIVE)
                .build();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.password(), user.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Account is not active");
    }
}
