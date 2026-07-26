package com.buslink.service.impl;

import com.buslink.dto.request.CreateRouteRequestDTO;
import com.buslink.dto.request.CreateRouteStopDTO;
import com.buslink.dto.request.UpdateRouteRequestDTO;
import com.buslink.dto.response.RouteResponseDTO;
import com.buslink.dto.response.RouteStopResponseDTO;
import com.buslink.entity.Route;
import com.buslink.entity.RouteStop;
import com.buslink.enums.RouteStatus;
import com.buslink.exception.ConflictException;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.exception.ValidationException;
import com.buslink.repository.RouteRepository;
import com.buslink.repository.RouteStopRepository;
import com.buslink.service.RouteService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RouteServiceImpl implements RouteService {

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;

    @Override
    @Transactional
    public RouteResponseDTO createRoute(CreateRouteRequestDTO request) {
        if (routeRepository.findByRouteNumber(request.routeNumber()).isPresent()) {
            throw new ConflictException(
                    "Route already exists with routeNumber: '%s'".formatted(request.routeNumber()));
        }

        validateNoDuplicateStops(request.stops());

        List<CreateRouteStopDTO> stops = request.stops();
        Route route = Route.builder()
                .routeNumber(request.routeNumber())
                .routeName(request.routeName())
                .farePerStage(request.farePerStage())
                .totalStops(stops.size())
                .originStop(stops.get(0).stopName())
                .destinationStop(stops.get(stops.size() - 1).stopName())
                .status(RouteStatus.ACTIVE)
                .build();
        route = routeRepository.save(route);

        UUID routeId = route.getRouteId();
        List<RouteStop> routeStops = stops.stream()
                .map(stop -> RouteStop.builder()
                        .routeId(routeId)
                        .stopName(stop.stopName())
                        .stopSequence(stop.stopSequence())
                        .stageNumber(stop.stageNumber())
                        .build())
                .toList();
        routeStopRepository.saveAll(routeStops);

        return toResponseDTO(route);
    }

    @Override
    public RouteResponseDTO getRouteById(UUID routeId) {
        return toResponseDTO(findById(routeId));
    }

    @Override
    public List<RouteResponseDTO> getAllRoutes() {
        return routeRepository.findAll().stream().map(this::toResponseDTO).toList();
    }

    @Override
    public RouteResponseDTO updateRoute(UUID routeId, UpdateRouteRequestDTO request) {
        Route route = findById(routeId);

        if (request.farePerStage() != null) {
            route.setFarePerStage(request.farePerStage());
        }
        if (request.status() != null) {
            route.setStatus(request.status());
        }

        return toResponseDTO(routeRepository.save(route));
    }

    @Override
    public RouteResponseDTO deleteRoute(UUID routeId) {
        Route route = findById(routeId);
        route.setStatus(RouteStatus.INACTIVE);
        return toResponseDTO(routeRepository.save(route));
    }

    @Override
    @Transactional
    public RouteStopResponseDTO addStop(UUID routeId, CreateRouteStopDTO request) {
        Route route = findById(routeId);

        if (routeStopRepository.findByRouteIdAndStopName(routeId, request.stopName()).isPresent()) {
            throw new ConflictException("Route already has a stop named '%s'".formatted(request.stopName()));
        }

        int expectedSequence = route.getTotalStops() + 1;
        if (!request.stopSequence().equals(expectedSequence)) {
            throw new ValidationException(
                    "stopSequence must be %d (stops can only be appended to the end of a route)"
                            .formatted(expectedSequence));
        }

        List<RouteStop> existingStops = routeStopRepository.findByRouteIdOrderByStopSequenceAsc(routeId);
        RouteStop currentLastStop = existingStops.get(existingStops.size() - 1);
        if (request.stageNumber() < currentLastStop.getStageNumber()) {
            throw new ValidationException(
                    ("stageNumber cannot be less than the current last stop's stageNumber (%d) — this stop belongs "
                                    + "in the middle of the route, which requires renumbering and isn't supported "
                                    + "by this endpoint")
                            .formatted(currentLastStop.getStageNumber()));
        }

        RouteStop routeStop = RouteStop.builder()
                .routeId(routeId)
                .stopName(request.stopName())
                .stopSequence(request.stopSequence())
                .stageNumber(request.stageNumber())
                .build();
        routeStop = routeStopRepository.save(routeStop);

        route.setTotalStops(expectedSequence);
        route.setDestinationStop(request.stopName());
        routeRepository.save(route);

        return toResponseDTO(routeStop);
    }

    private Route findById(UUID routeId) {
        return routeRepository
                .findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route", "routeId", routeId));
    }

    private void validateNoDuplicateStops(List<CreateRouteStopDTO> stops) {
        Set<Integer> sequences = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (CreateRouteStopDTO stop : stops) {
            if (!sequences.add(stop.stopSequence())) {
                throw new ValidationException(
                        "Duplicate stopSequence in request: %d".formatted(stop.stopSequence()));
            }
            if (!names.add(stop.stopName())) {
                throw new ValidationException("Duplicate stopName in request: '%s'".formatted(stop.stopName()));
            }
        }
    }

    private RouteStopResponseDTO toResponseDTO(RouteStop routeStop) {
        return new RouteStopResponseDTO(
                routeStop.getRouteStopId(),
                routeStop.getStopName(),
                routeStop.getStopSequence(),
                routeStop.getStageNumber());
    }

    private RouteResponseDTO toResponseDTO(Route route) {
        return new RouteResponseDTO(
                route.getRouteId(),
                route.getRouteNumber(),
                route.getRouteName(),
                route.getOriginStop(),
                route.getDestinationStop(),
                route.getTotalStops(),
                route.getFarePerStage(),
                route.getStatus());
    }
}
