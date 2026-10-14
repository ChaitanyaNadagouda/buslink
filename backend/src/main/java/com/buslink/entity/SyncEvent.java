package com.buslink.entity;

import com.buslink.enums.SyncStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "sync_event")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
public class SyncEvent extends BaseEntity implements Persistable<UUID> {

    /**
     * Client-assigned, NOT server-generated — the offline conductor device
     * creates this UUID locally so a re-uploaded (retried/duplicated) event
     * is recognizable as the same event. No {@code @GeneratedValue}: Hibernate
     * must persist exactly the id the client sent, never substitute its own.
     */
    @Id
    @EqualsAndHashCode.Include
    private UUID eventId;

    @Column(nullable = false)
    private UUID conductorId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String passengerQrToken;

    @Column(nullable = false)
    private UUID busId;

    @Column(nullable = false)
    private UUID routeId;

    @Column(nullable = false)
    private String originStop;

    @Column(nullable = false)
    private String destinationStop;

    /** Offline device timestamp — becomes Ticket.issuedAt on SYNCED/DISPUTED. */
    @Column(nullable = false)
    private Instant issuedAt;

    /** Logged for analytics only — never trusted; fare is always recalculated server-side. */
    @Column(precision = 12, scale = 2)
    private BigDecimal clientFare;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SyncStatus status;

    /** Set only on SYNCED or DISPUTED (both branches create a Ticket); null otherwise. */
    private UUID resultTicketId;

    /**
     * Why the last FAILED attempt failed — overwritten on every reprocess attempt. TEXT, not varchar(255):
     * the catch-all stores {@code ex.getMessage()}, and an unexpected DB/Hibernate message can exceed 255
     * chars — which would make the FAILED save itself fail. Kept in full for debugging.
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Count of FAILED (technical) outcomes. The retry scheduler stops picking the event once this reaches
     * sync.max-retry-attempts — a guard against "poison" events that fail the same way every time (e.g. a
     * code bug). A conductor re-upload still reprocesses it regardless. REJECTED never increments this.
     */
    @Column(nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    /**
     * Tells Spring Data whether {@code save()} should INSERT (persist) or SELECT-then-UPDATE (merge).
     * Needed because {@code eventId} is client-assigned and never null, so Spring Data's default
     * null-id check would always pick merge — and merge silently overwrites a row a concurrent request
     * just committed, bypassing the primary key. Starts {@code true} (built fresh = not in the DB yet);
     * flipped to {@code false} once loaded or handed to persist. Defaulting to true fails loudly
     * (duplicate key) rather than silently (overwrite) if ever wrong. Not a column.
     * See docs/SYNC_RACE_CONDITIONS.md, Case 3.
     */
    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PrePersist
    void markNotNew() {
        this.isNew = false;
    }
}
