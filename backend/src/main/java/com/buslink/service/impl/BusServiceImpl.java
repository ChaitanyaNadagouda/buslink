package com.buslink.service.impl;

import com.buslink.dto.request.CreateBusRequestDTO;
import com.buslink.dto.response.BusResponseDTO;
import com.buslink.entity.Bus;
import com.buslink.entity.Route;
import com.buslink.exception.ConflictException;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.repository.BusRepository;
import com.buslink.repository.RouteRepository;
import com.buslink.service.BusService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BusServiceImpl implements BusService {

    private final BusRepository busRepository;
    private final RouteRepository routeRepository;

    @Override
    public BusResponseDTO createBus(CreateBusRequestDTO request) {
        if (busRepository.findByBusNumber(request.busNumber()).isPresent()) {
            throw new ConflictException("Bus already exists with busNumber: '%s'".formatted(request.busNumber()));
        }

        Route route = routeRepository
                .findById(request.routeId())
                .orElseThrow(() -> new ResourceNotFoundException("Route", "routeId", request.routeId()));

        Bus bus = Bus.builder()
                .busNumber(request.busNumber())
                .routeId(route.getRouteId())
                .build();
        bus = busRepository.save(bus);

        return toResponseDTO(bus, route.getRouteNumber());
    }

    @Override
    public List<BusResponseDTO> getAllBuses() {
        return busRepository.findAll().stream().map(this::toResponseDTO).toList();
    }

    @Override
    public BusResponseDTO getBusByBusId(UUID busId) {
        return toResponseDTO(findById(busId));
    }

    private Bus findById(UUID busId) {
        return busRepository.findById(busId).orElseThrow(() -> new ResourceNotFoundException("Bus", "busId", busId));
    }

    private BusResponseDTO toResponseDTO(Bus bus) {
        Route route = routeRepository
                .findById(bus.getRouteId())
                .orElseThrow(() -> new ResourceNotFoundException("Route", "routeId", bus.getRouteId()));
        return toResponseDTO(bus, route.getRouteNumber());
    }

    private BusResponseDTO toResponseDTO(Bus bus, String routeNumber) {
        return new BusResponseDTO(bus.getBusId(), bus.getBusNumber(), bus.getRouteId(), routeNumber);
    }
}
