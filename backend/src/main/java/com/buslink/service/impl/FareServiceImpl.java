package com.buslink.service.impl;

import com.buslink.dto.response.FareRateDTO;
import com.buslink.dto.response.FareResponseDTO;
import com.buslink.dto.response.RouteStopResponseDTO;
import com.buslink.entity.Route;
import com.buslink.entity.RouteStop;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.exception.ValidationException;
import com.buslink.repository.RouteRepository;
import com.buslink.repository.RouteStopRepository;
import com.buslink.service.FareService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class FareServiceImpl implements FareService {

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final FareService self;

    public FareServiceImpl(
            RouteRepository routeRepository, RouteStopRepository routeStopRepository, @Lazy FareService self) {
        this.routeRepository = routeRepository;
        this.routeStopRepository = routeStopRepository;
        this.self = self;
    }

    @Override
    @Cacheable(value = "route-stops", key = "#routeId")
    public List<RouteStopResponseDTO> getStopsForRoute(UUID routeId) {
        return routeStopRepository.findByRouteIdOrderByStopSequenceAsc(routeId).stream()
                .map(this::toResponseDTO)
                .toList();
    }

    @Override
    public List<RouteStopResponseDTO> searchStops(UUID routeId, String search) {
        return routeStopRepository
                .findByRouteIdAndStopNameStartingWithIgnoreCaseOrderByStopSequenceAsc(routeId, search)
                .stream()
                .map(this::toResponseDTO)
                .toList();
    }

    @Override
    public List<RouteStopResponseDTO> searchStopsAfter(UUID routeId, String originStop, String search) {
        RouteStop origin = findStopByName(routeId, originStop);

        return routeStopRepository
                .findByRouteIdAndStopSequenceGreaterThanAndStopNameStartingWithIgnoreCaseOrderByStopSequenceAsc(
                        routeId, origin.getStopSequence(), search)
                .stream()
                .map(this::toResponseDTO)
                .toList();
    }

    @Override
    @Cacheable(value = "fare-calc", key = "#routeId + '-' + #originStop + '-' + #destinationStop")
    public FareRateDTO getFareRate(UUID routeId, String originStop, String destinationStop) {
        RouteStop origin = findStopByName(routeId, originStop);
        RouteStop destination = findStopByName(routeId, destinationStop);

        if (destination.getStopSequence() <= origin.getStopSequence()) {
            throw new ValidationException("Destination must be after origin");
        }

        Route route = routeRepository
                .findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route", "routeId", routeId));

        int stagesCrossed = (destination.getStageNumber() - origin.getStageNumber()) + 1;
        BigDecimal adultFare = route.getFarePerStage().multiply(BigDecimal.valueOf(stagesCrossed));
        BigDecimal childFare = adultFare.divide(BigDecimal.valueOf(2), 2, RoundingMode.CEILING);
        BigDecimal infantFare = BigDecimal.ZERO;

        return new FareRateDTO(
                origin.getStopName(), destination.getStopName(), stagesCrossed, adultFare, childFare, infantFare);
    }

    @Override
    public FareResponseDTO calculateFare(
            UUID routeId, String originStop, String destinationStop, int adults, int children, int infants) {
        FareRateDTO rate = self.getFareRate(routeId, originStop, destinationStop);

        BigDecimal totalFare = rate.adultFare()
                .multiply(BigDecimal.valueOf(adults))
                .add(rate.childFare().multiply(BigDecimal.valueOf(children)));

        return new FareResponseDTO(
                rate.originStop(), rate.destinationStop(), rate.stagesCrossed(), rate.adultFare(), rate.childFare(),
                rate.infantFare(), totalFare);
    }

    private RouteStop findStopByName(UUID routeId, String stopName) {
        return routeStopRepository
                .findByRouteIdAndStopName(routeId, stopName)
                .orElseThrow(() -> new ResourceNotFoundException("RouteStop", "stopName", stopName));
    }

    private RouteStopResponseDTO toResponseDTO(RouteStop routeStop) {
        return new RouteStopResponseDTO(
                routeStop.getRouteStopId(),
                routeStop.getStopName(),
                routeStop.getStopSequence(),
                routeStop.getStageNumber());
    }
}
