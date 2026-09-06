package com.buslink.dto.response;

import java.util.UUID;

public record TopRouteDTO(UUID routeId, String routeName, Long ticketCount) {}
