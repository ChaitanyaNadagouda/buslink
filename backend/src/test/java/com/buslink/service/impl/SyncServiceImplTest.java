package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buslink.dto.request.SyncBatchRequestDTO;
import com.buslink.dto.request.SyncEventRequestDTO;
import com.buslink.dto.response.SyncBatchResponseDTO;
import com.buslink.dto.response.SyncEventResultDTO;
import com.buslink.enums.SyncStatus;
import com.buslink.repository.SyncEventRepository;
import com.buslink.service.SyncEventProcessor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Batch-loop isolation (docs/SYNC_RACE_CONDITIONS.md, Q13–Q14): an exception escaping processEvent —
 * e.g. a duplicate key at commit after losing a concurrent race — must not skip the rest of the batch,
 * and must not write anything to the DB.
 */
@ExtendWith(MockitoExtension.class)
class SyncServiceImplTest {

    @Mock
    private SyncEventProcessor syncEventProcessor;

    @Mock
    private SyncEventRepository syncEventRepository;

    @InjectMocks
    private SyncServiceImpl syncService;

    private final UUID conductorId = UUID.randomUUID();

    @Test
    void processSyncBatch_eventLosesConcurrentRace_restOfBatchStillProcessed() {
        SyncEventRequestDTO first = eventRequest();
        SyncEventRequestDTO loser = eventRequest();
        SyncEventRequestDTO third = eventRequest();

        when(syncEventProcessor.processEvent(first, conductorId))
                .thenReturn(new SyncEventResultDTO(first.eventId(), SyncStatus.SYNCED, UUID.randomUUID(), "ok"));
        when(syncEventProcessor.processEvent(loser, conductorId))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates sync_event_pkey"));
        when(syncEventProcessor.processEvent(third, conductorId))
                .thenReturn(new SyncEventResultDTO(third.eventId(), SyncStatus.DISPUTED, UUID.randomUUID(), "x"));

        SyncBatchResponseDTO response =
                syncService.processSyncBatch(new SyncBatchRequestDTO(List.of(first, loser, third)), conductorId);

        assertThat(response.totalEvents()).isEqualTo(3);
        assertThat(response.synced()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.disputed()).isEqualTo(1);

        SyncEventResultDTO loserResult = response.results().get(1);
        assertThat(loserResult.eventId()).isEqualTo(loser.eventId());
        assertThat(loserResult.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(loserResult.ticketId()).isNull();
        assertThat(loserResult.message()).isEqualTo("Could not be processed — please re-upload");

        // The event after the loser was still processed.
        verify(syncEventProcessor).processEvent(third, conductorId);
        // Nothing written for the loser: the DB may hold the winner's committed result.
        verifyNoInteractions(syncEventRepository);
    }

    @Test
    void processSyncBatch_unexpectedException_reportedAsFailedAndBatchContinues() {
        SyncEventRequestDTO broken = eventRequest();
        SyncEventRequestDTO next = eventRequest();

        when(syncEventProcessor.processEvent(broken, conductorId)).thenThrow(new NullPointerException("bug"));
        when(syncEventProcessor.processEvent(eq(next), any(UUID.class)))
                .thenReturn(new SyncEventResultDTO(next.eventId(), SyncStatus.SYNCED, UUID.randomUUID(), "ok"));

        SyncBatchResponseDTO response =
                syncService.processSyncBatch(new SyncBatchRequestDTO(List.of(broken, next)), conductorId);

        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.synced()).isEqualTo(1);
        // The raw exception text never reaches the client.
        assertThat(response.results().get(0).message()).isEqualTo("Could not be processed — please re-upload");
    }

    private SyncEventRequestDTO eventRequest() {
        return new SyncEventRequestDTO(
                UUID.randomUUID(),
                conductorId,
                UUID.randomUUID(),
                "qr-token",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Stop A",
                "Stop C",
                Instant.now(),
                new BigDecimal("90.00"));
    }
}
