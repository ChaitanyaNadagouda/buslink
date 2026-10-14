package com.buslink.service.impl;

import com.buslink.dto.request.SyncBatchRequestDTO;
import com.buslink.dto.request.SyncEventRequestDTO;
import com.buslink.dto.response.SyncBatchResponseDTO;
import com.buslink.dto.response.SyncEventResultDTO;
import com.buslink.dto.response.SyncStatusResponseDTO;
import com.buslink.enums.SyncStatus;
import com.buslink.repository.SyncEventRepository;
import com.buslink.service.SyncEventProcessor;
import com.buslink.service.SyncService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncServiceImpl implements SyncService {

    private final SyncEventProcessor syncEventProcessor;
    private final SyncEventRepository syncEventRepository;

    @Override
    // Deliberately NOT @Transactional — each event commits/rolls back independently through
    // SyncEventProcessor's own @Transactional boundary. Wrapping this loop in a transaction would
    // re-couple every event's outcome to the whole batch, defeating the entire point of per-event isolation.
    public SyncBatchResponseDTO processSyncBatch(SyncBatchRequestDTO batch, UUID conductorId) {
        List<SyncEventResultDTO> results = new ArrayList<>();
        int synced = 0;
        int failed = 0;
        int disputed = 0;
        int rejected = 0;

        for (SyncEventRequestDTO event : batch.events()) {
            SyncEventResultDTO result = processIsolated(event, conductorId);
            results.add(result);
            // processEvent only ever returns SYNCED, FAILED, DISPUTED or REJECTED — PENDING/PROCESSING
            // are internal, in-flight states never surfaced in a result.
            switch (result.status()) {
                case SYNCED -> synced++;
                case FAILED -> failed++;
                case DISPUTED -> disputed++;
                case REJECTED -> rejected++;
            }
        }

        return new SyncBatchResponseDTO(batch.events().size(), synced, failed, disputed, rejected, results);
    }

    /**
     * Catches what processEvent's own try/catch can't: errors thrown at COMMIT (by the transaction proxy,
     * after the method body returns) or at its early saveAndFlush — typically a concurrent request for the
     * same eventId winning the race. Catches everything so one bad event never skips the rest of the batch;
     * unexpected errors are logged at ERROR so real bugs stay visible. Deliberately saves nothing: the
     * transaction already rolled back, and the DB may now hold another request's committed result that a
     * FAILED write would overwrite (and the retry scheduler would then reprocess → double charge).
     * See docs/SYNC_RACE_CONDITIONS.md, Q13–Q14.
     */
    private SyncEventResultDTO processIsolated(SyncEventRequestDTO event, UUID conductorId) {
        try {
            return syncEventProcessor.processEvent(event, conductorId);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException ex) {
            // Not necessarily a race: Spring maps duplicate keys, NOT NULL and CHECK violations all to
            // DataIntegrityViolationException — the stack trace says which.
            log.warn("Sync event {} hit a data-integrity or locking conflict (most likely a concurrent duplicate); "
                    + "client should re-upload", event.eventId(), ex);
        } catch (Exception ex) {
            log.error("Sync event {} failed outside the per-event transaction", event.eventId(), ex);
        }
        return new SyncEventResultDTO(
                event.eventId(), SyncStatus.FAILED, null, "Could not be processed — please re-upload");
    }

    @Override
    public SyncStatusResponseDTO getSyncStatus(UUID conductorId) {
        Map<SyncStatus, Long> counts = new EnumMap<>(SyncStatus.class);
        for (SyncStatus status : SyncStatus.values()) {
            counts.put(status, 0L);
        }
        for (Object[] row : syncEventRepository.countByStatusForConductor(conductorId)) {
            counts.put((SyncStatus) row[0], (Long) row[1]);
        }

        return new SyncStatusResponseDTO(
                conductorId,
                counts.get(SyncStatus.PENDING),
                counts.get(SyncStatus.PROCESSING),
                counts.get(SyncStatus.SYNCED),
                counts.get(SyncStatus.FAILED),
                counts.get(SyncStatus.DISPUTED),
                counts.get(SyncStatus.REJECTED));
    }
}
