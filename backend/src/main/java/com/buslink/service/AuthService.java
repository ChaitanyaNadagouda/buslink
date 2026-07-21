package com.buslink.service;

import com.buslink.dto.request.RefreshTokenRequestDTO;
import com.buslink.dto.request.UserLoginRequestDTO;
import com.buslink.dto.request.UserSignUpRequestDTO;
import com.buslink.dto.response.AuthResponseDTO;

public interface AuthService {

    AuthResponseDTO register(UserSignUpRequestDTO request);

    AuthResponseDTO login(UserLoginRequestDTO request);

    AuthResponseDTO refreshToken(RefreshTokenRequestDTO request);
}
