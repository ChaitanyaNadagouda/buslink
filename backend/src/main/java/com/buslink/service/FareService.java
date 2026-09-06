package com.buslink.service;

import com.buslink.dto.response.FareRateDTO;
import com.buslink.dto.response.FareResponseDTO;
import com.buslink.dto.response.RouteStopResponseDTO;
import java.util.List;
import java.util.UUID;

public interface FareService {

    List<RouteStopResponseDTO> getStopsForRoute(UUID routeId);

    List<RouteStopResponseDTO> searchStops(UUID routeId, String search);

    List<RouteStopResponseDTO> searchStopsAfter(UUID routeId, String originStop, String search);

    FareRateDTO getFareRate(UUID routeId, String originStop, String destinationStop);

    FareResponseDTO calculateFare(
            UUID routeId, String originStop, String destinationStop, int adults, int children, int infants);
}
