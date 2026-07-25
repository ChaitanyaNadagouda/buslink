package com.buslink.dto.response;

import java.util.UUID;

public record BusResponseDTO(UUID busId, String busNumber, UUID routeId, String routeNumber) {}
