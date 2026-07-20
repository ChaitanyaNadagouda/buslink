package com.buslink.service.impl;

import com.buslink.dto.request.RefreshTokenRequestDTO;
import com.buslink.dto.request.UserLoginRequestDTO;
import com.buslink.dto.request.UserSignUpRequestDTO;
import com.buslink.dto.response.AuthResponseDTO;
import com.buslink.entity.User;
import com.buslink.entity.Wallet;
import com.buslink.enums.UserStatus;
import com.buslink.enums.WalletStatus;
import com.buslink.exception.ValidationException;
import com.buslink.repository.UserRepository;
import com.buslink.repository.WalletRepository;
import com.buslink.security.JwtUtil;
import com.buslink.service.AuthService;
import com.buslink.util.QrTokenUtil;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    @Transactional
    public AuthResponseDTO register(UserSignUpRequestDTO request) {
        userRepository.findByEmail(request.email()).ifPresent(existing -> {
            throw new ValidationException("Email already registered: '" + request.email() + "'");
        });

        User user = User.builder()
                .name(request.name())
                .email(request.email())
                .mobileNo(request.mobileNo())
                .passwordHash(passwordEncoder.encode(request.password()))
                .qrToken(QrTokenUtil.generateQrToken())
                .status(UserStatus.ACTIVE)
                .build();
        user = userRepository.save(user);

        Wallet wallet = Wallet.builder()
                .userId(user.getUserId())
                .balance(BigDecimal.ZERO)
                .status(WalletStatus.ACTIVE)
                .build();
        walletRepository.save(wallet);

        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        return new AuthResponseDTO(accessToken, refreshToken, user.getUserId(), user.getEmail(), user.getName());
    }

    @Override
    public AuthResponseDTO login(UserLoginRequestDTO request) {
        User user = userRepository
                .findByEmail(request.email())
                .orElseThrow(() -> new ValidationException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ValidationException("Invalid email or password");
        }

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ValidationException("Account is not active");
        }

        String accessToken = jwtUtil.generateAccessToken(user);
        String refreshToken = jwtUtil.generateRefreshToken(user);

        return new AuthResponseDTO(accessToken, refreshToken, user.getUserId(), user.getEmail(), user.getName());
    }

    @Override
    public AuthResponseDTO refreshToken(RefreshTokenRequestDTO request) {
        String token = request.refreshToken();

        if (!jwtUtil.isRefreshToken(token)) {
            throw new ValidationException("Invalid or expired refresh token");
        }

        String email = jwtUtil.extractUsername(token);

        User user = userRepository
                .findByEmail(email)
                .orElseThrow(() -> new ValidationException("Invalid or expired refresh token"));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ValidationException("Invalid or expired refresh token");
        }

        String newAccessToken = jwtUtil.generateAccessToken(user);

        return new AuthResponseDTO(newAccessToken, token, user.getUserId(), user.getEmail(), user.getName());
    }
}
