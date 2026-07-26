package com.buslink.service;

import com.buslink.dto.request.CreateRouteRequestDTO;
import com.buslink.dto.request.CreateRouteStopDTO;
import com.buslink.dto.request.UpdateRouteRequestDTO;
import com.buslink.dto.response.RouteResponseDTO;
import com.buslink.dto.response.RouteStopResponseDTO;
import java.util.List;
import java.util.UUID;

public interface RouteService {

    RouteResponseDTO createRoute(CreateRouteRequestDTO request);

    RouteResponseDTO getRouteById(UUID routeId);

    List<RouteResponseDTO> getAllRoutes();

    RouteResponseDTO updateRoute(UUID routeId, UpdateRouteRequestDTO request);

    RouteResponseDTO deleteRoute(UUID routeId);

    RouteStopResponseDTO addStop(UUID routeId, CreateRouteStopDTO request);
}
