package com.buslink.service.impl;

import com.buslink.dto.response.UserProfileResponseDTO;
import com.buslink.entity.User;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.exception.ValidationException;
import com.buslink.repository.UserRepository;
import com.buslink.security.JwtUtil;
import com.buslink.service.UserService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    @Override
    public User findById(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", userId));
    }

    @Override
    public User findByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    @Override
    public UserProfileResponseDTO getUserProfile(UUID userId) {
        User user = findById(userId);
        return new UserProfileResponseDTO(
                user.getUserId(),
                user.getName(),
                user.getEmail(),
                user.getMobileNo(),
                user.getStatus(),
                user.getQrToken(),
                user.getCreatedAt());
    }

    @Override
    public String getQrToken(UUID userId) {
        User user = findById(userId);
        try {
            jwtUtil.validatePassengerQrToken(user.getQrToken());
            return user.getQrToken();
        } catch (ValidationException e) {
            return regenerateQrToken(user);
        }
    }

    @Override
    public String refreshQrToken(UUID userId) {
        return regenerateQrToken(findById(userId));
    }

    private String regenerateQrToken(User user) {
        String qrToken = jwtUtil.generatePassengerQrToken(user);
        user.setQrToken(qrToken);
        userRepository.save(user);
        return qrToken;
    }
}
