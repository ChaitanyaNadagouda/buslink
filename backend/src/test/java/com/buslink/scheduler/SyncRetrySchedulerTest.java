package com.buslink.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buslink.config.SyncProperties;
import com.buslink.dto.request.SyncEventRequestDTO;
import com.buslink.entity.SyncEvent;
import com.buslink.enums.SyncStatus;
import com.buslink.repository.SyncEventRepository;
import com.buslink.service.SyncEventProcessor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Retry-loop isolation (docs/SYNC_RACE_CONDITIONS.md, Part C): one event's commit-time failure must not
 * end the run and leave the remaining FAILED events waiting another interval.
 */
@ExtendWith(MockitoExtension.class)
class SyncRetrySchedulerTest {

    @Mock
    private SyncEventRepository syncEventRepository;

    @Mock
    private SyncEventProcessor syncEventProcessor;

    @Mock
    private SyncProperties syncProperties;

    @InjectMocks
    private SyncRetryScheduler syncRetryScheduler;

    @Test
    void retryFailedEvents_oneEventThrows_remainingEventsStillRetried() {
        SyncEvent first = failedEvent();
        SyncEvent second = failedEvent();
        when(syncProperties.maxRetryAttempts()).thenReturn(10);
        when(syncEventRepository.findByStatusAndFailedAttemptsLessThanOrderByCreatedAtAsc(SyncStatus.FAILED, 10))
                .thenReturn(List.of(first, second));
        // First event loses a race at commit; the second succeeds (result unused by the scheduler).
        when(syncEventProcessor.processEvent(any(SyncEventRequestDTO.class), any(UUID.class)))
                .thenAnswer(inv -> {
                    SyncEventRequestDTO request = inv.getArgument(0);
                    if (request.eventId().equals(first.getEventId())) {
                        throw new ObjectOptimisticLockingFailureException(SyncEvent.class, first.getEventId());
                    }
                    return null;
                });

        syncRetryScheduler.retryFailedEvents();

        verify(syncEventProcessor).processEvent(
                argThat(r -> r != null && r.eventId().equals(second.getEventId())), eq(second.getConductorId()));
    }

    private SyncEvent failedEvent() {
        return SyncEvent.builder()
                .eventId(UUID.randomUUID())
                .conductorId(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .passengerQrToken("qr-token")
                .busId(UUID.randomUUID())
                .routeId(UUID.randomUUID())
                .originStop("Stop A")
                .destinationStop("Stop C")
                .issuedAt(Instant.now())
                .status(SyncStatus.FAILED)
                .build();
    }
}
