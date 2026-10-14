package com.buslink.scheduler;

import com.buslink.config.SyncProperties;
import com.buslink.dto.request.SyncEventRequestDTO;
import com.buslink.entity.SyncEvent;
import com.buslink.enums.SyncStatus;
import com.buslink.repository.SyncEventRepository;
import com.buslink.service.SyncEventProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** {@code @EnableScheduling} lives here, not on the main class — same placement convention as
 *  {@code @EnableCaching} on {@code RedisConfig}. */
@Component
@EnableScheduling
@RequiredArgsConstructor
@Slf4j
public class SyncRetryScheduler {

    private final SyncEventRepository syncEventRepository;
    private final SyncEventProcessor syncEventProcessor;
    private final SyncProperties syncProperties;

    @Scheduled(fixedDelayString = "${sync.retry-interval-ms}")
    public void retryFailedEvents() {
        // Only FAILED (technical) — never DISPUTED (a wallet shortfall doesn't fix itself on a timer) or
        // REJECTED (bad data fails identically every time). Capped events are skipped; a re-upload still works.
        List<SyncEvent> failedEvents = syncEventRepository.findByStatusAndFailedAttemptsLessThanOrderByCreatedAtAsc(
                SyncStatus.FAILED, syncProperties.maxRetryAttempts());
        if (failedEvents.isEmpty()) {
            return;
        }

        int errored = 0;
        for (SyncEvent syncEvent : failedEvents) {
            // Per-event try/catch: a commit-time error (e.g. a conductor re-upload of the same event winning
            // the race) must not end this run and leave the remaining FAILED events waiting another interval.
            // Nothing is saved on error — the transaction rolled back and the DB already holds the truth.
            try {
                syncEventProcessor.processEvent(toRequestDTO(syncEvent), syncEvent.getConductorId());
            } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException ex) {
                errored++;
                log.warn("SyncRetryScheduler: event {} hit a data-integrity or locking conflict "
                        + "(most likely a concurrent duplicate)", syncEvent.getEventId(), ex);
            } catch (Exception ex) {
                errored++;
                log.error("SyncRetryScheduler: event {} failed outside the per-event transaction",
                        syncEvent.getEventId(), ex);
            }
        }
        log.info("SyncRetryScheduler: retried {} FAILED event(s), {} errored", failedEvents.size(), errored);
    }

    private SyncEventRequestDTO toRequestDTO(SyncEvent syncEvent) {
        return new SyncEventRequestDTO(
                syncEvent.getEventId(),
                syncEvent.getConductorId(),
                syncEvent.getUserId(),
                syncEvent.getPassengerQrToken(),
                syncEvent.getBusId(),
                syncEvent.getRouteId(),
                syncEvent.getOriginStop(),
                syncEvent.getDestinationStop(),
                syncEvent.getIssuedAt(),
                syncEvent.getClientFare());
    }
}
