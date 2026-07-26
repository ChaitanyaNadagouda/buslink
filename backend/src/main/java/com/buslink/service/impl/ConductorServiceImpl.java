package com.buslink.service.impl;

import com.buslink.dto.request.ConductorLoginRequestDTO;
import com.buslink.dto.response.ConductorAuthResponseDTO;
import com.buslink.dto.response.ConductorResponseDTO;
import com.buslink.entity.Bus;
import com.buslink.entity.Conductor;
import com.buslink.enums.ConductorStatus;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.exception.ValidationException;
import com.buslink.repository.BusRepository;
import com.buslink.repository.ConductorRepository;
import com.buslink.security.JwtUtil;
import com.buslink.service.ConductorService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConductorServiceImpl implements ConductorService {

    private final ConductorRepository conductorRepository;
    private final BusRepository busRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    public ConductorAuthResponseDTO login(ConductorLoginRequestDTO request) {
        Conductor conductor = conductorRepository
                .findByEmail(request.email())
                .orElseThrow(() -> new ValidationException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), conductor.getPasswordHash())) {
            throw new ValidationException("Invalid email or password");
        }

        if (conductor.getStatus() != ConductorStatus.ACTIVE) {
            throw new ValidationException("Account is not active");
        }

        if (conductor.getBusId() == null) {
            throw new ValidationException("Conductor is not assigned to a bus");
        }

        Bus bus = busRepository
                .findById(conductor.getBusId())
                .orElseThrow(() -> new ResourceNotFoundException("Bus", "busId", conductor.getBusId()));

        String accessToken = jwtUtil.generateConductorAccessToken(conductor);
        String refreshToken = jwtUtil.generateConductorRefreshToken(conductor);

        return new ConductorAuthResponseDTO(
                accessToken,
                refreshToken,
                conductor.getConductorId(),
                conductor.getName(),
                conductor.getEmail(),
                conductor.getBusId(),
                bus.getRouteId());
    }

    @Override
    public ConductorResponseDTO getConductorProfile(UUID conductorId) {
        return toResponseDTO(findById(conductorId));
    }

    @Override
    public ConductorResponseDTO assignBus(UUID conductorId, UUID busId) {
        Conductor conductor = findById(conductorId);
        busRepository.findById(busId).orElseThrow(() -> new ResourceNotFoundException("Bus", "busId", busId));

        conductor.setBusId(busId);
        conductor = conductorRepository.save(conductor);

        return toResponseDTO(conductor);
    }

    private Conductor findById(UUID conductorId) {
        return conductorRepository
                .findById(conductorId)
                .orElseThrow(() -> new ResourceNotFoundException("Conductor", "conductorId", conductorId));
    }

    private ConductorResponseDTO toResponseDTO(Conductor conductor) {
        return new ConductorResponseDTO(
                conductor.getConductorId(),
                conductor.getName(),
                conductor.getEmail(),
                conductor.getBusId(),
                conductor.getStatus());
    }
}
