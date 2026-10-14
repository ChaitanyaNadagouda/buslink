package com.buslink.dto.response;

import java.util.List;

public record SyncBatchResponseDTO(
        int totalEvents, int synced, int failed, int disputed, int rejected, List<SyncEventResultDTO> results) {}
