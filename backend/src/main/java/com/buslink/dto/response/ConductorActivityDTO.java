package com.buslink.dto.response;

import java.util.UUID;

public record ConductorActivityDTO(UUID conductorId, String conductorName, Long ticketsIssued) {}
