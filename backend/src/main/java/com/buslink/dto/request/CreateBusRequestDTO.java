package com.buslink.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateBusRequestDTO(@NotBlank String busNumber, @NotNull UUID routeId) {}
