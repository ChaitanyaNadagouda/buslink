package com.buslink.dto.response;

import java.util.UUID;

public record RouteStopResponseDTO(
        UUID routeStopId, String stopName, Integer stopSequence, Integer stageNumber) {}
