package com.buslink.service.impl;

import com.buslink.dto.request.AdminLoginRequestDTO;
import com.buslink.dto.response.AdminAuthResponseDTO;
import com.buslink.entity.Admin;
import com.buslink.enums.AdminStatus;
import com.buslink.exception.ValidationException;
import com.buslink.repository.AdminRepository;
import com.buslink.security.JwtUtil;
import com.buslink.service.AdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final AdminRepository adminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public AdminAuthResponseDTO login(AdminLoginRequestDTO request) {
        Admin admin = adminRepository
                .findByEmail(request.email())
                .orElseThrow(() -> new ValidationException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            throw new ValidationException("Invalid email or password");
        }

        if (admin.getStatus() != AdminStatus.ACTIVE) {
            throw new ValidationException("Account is not active");
        }

        String accessToken = jwtUtil.generateAdminAccessToken(admin);
        String refreshToken = jwtUtil.generateAdminRefreshToken(admin);

        return new AdminAuthResponseDTO(
                accessToken, refreshToken, admin.getAdminId(), admin.getName(), admin.getEmail());
    }
}
