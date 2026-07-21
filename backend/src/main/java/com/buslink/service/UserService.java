package com.buslink.service;

import com.buslink.dto.response.UserProfileResponseDTO;
import com.buslink.entity.User;
import java.util.UUID;

public interface UserService {

    User findById(UUID userId);

    User findByEmail(String email);

    UserProfileResponseDTO getUserProfile(UUID userId);

    String getQrToken(UUID userId);
}
