package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buslink.entity.User;
import com.buslink.enums.UserStatus;
import com.buslink.exception.ValidationException;
import com.buslink.repository.UserRepository;
import com.buslink.security.JwtUtil;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void getQrToken_returnsStoredToken_whenStillValid() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .userId(userId)
                .status(UserStatus.ACTIVE)
                .qrToken("still-valid-jwt")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        String result = userService.getQrToken(userId);

        assertThat(result).isEqualTo("still-valid-jwt");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void getQrToken_regeneratesAndPersists_whenStoredTokenExpiredOrInvalid() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .userId(userId)
                .status(UserStatus.ACTIVE)
                .qrToken("expired-jwt")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtUtil.validatePassengerQrToken("expired-jwt"))
                .thenThrow(new ValidationException("Invalid or expired passenger QR token"));
        when(jwtUtil.generatePassengerQrToken(user)).thenReturn("fresh-jwt");

        String result = userService.getQrToken(userId);

        assertThat(result).isEqualTo("fresh-jwt");
        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getQrToken()).isEqualTo("fresh-jwt");
    }

    @Test
    void refreshQrToken_alwaysRegenerates_regardlessOfCurrentTokenValidity() {
        UUID userId = UUID.randomUUID();
        User user = User.builder()
                .userId(userId)
                .status(UserStatus.ACTIVE)
                .qrToken("still-valid-jwt")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(jwtUtil.generatePassengerQrToken(user)).thenReturn("force-refreshed-jwt");

        String result = userService.refreshQrToken(userId);

        assertThat(result).isEqualTo("force-refreshed-jwt");
        assertThat(user.getQrToken()).isEqualTo("force-refreshed-jwt");
        verify(userRepository).save(user);
    }
}
