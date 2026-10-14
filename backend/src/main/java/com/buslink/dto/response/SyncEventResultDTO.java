package com.buslink.dto.response;

import com.buslink.enums.SyncStatus;
import java.util.UUID;

public record SyncEventResultDTO(UUID eventId, SyncStatus status, UUID ticketId, String message) {}
