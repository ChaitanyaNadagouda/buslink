package com.buslink.repository;

import com.buslink.entity.SyncEvent;
import com.buslink.enums.SyncStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncEventRepository extends JpaRepository<SyncEvent, UUID> {

    /**
     * Not yet called by any Sprint 7 task — kept for a future per-conductor
     * status-detail view (e.g. "show my 3 PENDING events"), as opposed to
     * {@link #countByStatusForConductor} which only returns counts.
     */
    List<SyncEvent> findByConductorIdAndStatusOrderByCreatedAtAsc(UUID conductorId, SyncStatus status);

    /**
     * Retry scheduler — FAILED events across every conductor still under the attempt cap. Capped ("poison")
     * events are skipped here but stay FAILED, so a conductor re-upload can still reprocess them.
     */
    List<SyncEvent> findByStatusAndFailedAttemptsLessThanOrderByCreatedAtAsc(SyncStatus status, int maxAttempts);

    @Query("SELECT s.status, COUNT(s) FROM SyncEvent s WHERE s.conductorId = :conductorId GROUP BY s.status")
    List<Object[]> countByStatusForConductor(@Param("conductorId") UUID conductorId);

    /**
     * Idempotency check for sync processing — {@code SELECT ... FOR UPDATE}. A second request for the
     * same event waits here until the first commits, then reads its committed status instead of racing
     * past a stale FAILED. A separate method, not an override of {@code findById}, so plain reads never
     * pay for the lock. Locks nothing when the row doesn't exist yet — brand-new events rely on the
     * primary key instead (see {@code SyncEvent#isNew}). Full writeup: docs/SYNC_RACE_CONDITIONS.md.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SyncEvent s WHERE s.eventId = :eventId")
    Optional<SyncEvent> findByIdForUpdate(@Param("eventId") UUID eventId);
}
