package com.buslink.service;

import com.buslink.dto.request.SyncBatchRequestDTO;
import com.buslink.dto.response.SyncBatchResponseDTO;
import com.buslink.dto.response.SyncStatusResponseDTO;
import java.util.UUID;

public interface SyncService {

    SyncBatchResponseDTO processSyncBatch(SyncBatchRequestDTO batch, UUID conductorId);

    SyncStatusResponseDTO getSyncStatus(UUID conductorId);
}
