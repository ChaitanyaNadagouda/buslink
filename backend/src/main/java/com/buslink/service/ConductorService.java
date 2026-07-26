package com.buslink.service;

import com.buslink.dto.request.ConductorLoginRequestDTO;
import com.buslink.dto.response.ConductorAuthResponseDTO;
import com.buslink.dto.response.ConductorResponseDTO;
import java.util.UUID;

public interface ConductorService {

    ConductorAuthResponseDTO login(ConductorLoginRequestDTO request);

    ConductorResponseDTO getConductorProfile(UUID conductorId);

    ConductorResponseDTO assignBus(UUID conductorId, UUID busId);
}
