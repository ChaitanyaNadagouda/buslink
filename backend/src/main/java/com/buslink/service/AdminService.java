package com.buslink.service;

import com.buslink.dto.request.AdminLoginRequestDTO;
import com.buslink.dto.response.AdminAuthResponseDTO;

public interface AdminService {

    AdminAuthResponseDTO login(AdminLoginRequestDTO request);
}
