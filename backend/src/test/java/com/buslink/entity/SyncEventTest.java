package com.buslink.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Persistable isNew flag (docs/SYNC_RACE_CONDITIONS.md, Case 3): a freshly built event must make
 * save() INSERT (so a concurrent duplicate hits the primary key instead of being merged over), and
 * a loaded or already-persisted one must make it UPDATE.
 */
class SyncEventTest {

    @Test
    void builtFresh_isNew_soSaveInserts() {
        SyncEvent syncEvent = SyncEvent.builder().eventId(UUID.randomUUID()).build();

        // Guards the Lombok trap: without @Builder.Default the builder would ignore "= true".
        assertThat(syncEvent.isNew()).isTrue();
    }

    @Test
    void afterLoadOrPersistCallback_isNotNew_soSaveUpdates() {
        SyncEvent syncEvent = SyncEvent.builder().eventId(UUID.randomUUID()).build();

        syncEvent.markNotNew(); // what @PostLoad / @PrePersist invoke

        assertThat(syncEvent.isNew()).isFalse();
    }

    @Test
    void getId_returnsClientAssignedEventId() {
        UUID eventId = UUID.randomUUID();

        assertThat(SyncEvent.builder().eventId(eventId).build().getId()).isEqualTo(eventId);
    }
}
