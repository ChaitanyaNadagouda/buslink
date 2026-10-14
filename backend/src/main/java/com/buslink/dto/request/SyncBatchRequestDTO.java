package com.buslink.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record SyncBatchRequestDTO(@NotEmpty @Valid List<SyncEventRequestDTO> events) {}
