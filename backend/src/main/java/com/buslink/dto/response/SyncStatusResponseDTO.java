package com.buslink.dto.response;

import java.util.UUID;

public record SyncStatusResponseDTO(
        UUID conductorId, long pending, long processing, long synced, long failed, long disputed, long rejected) {}
