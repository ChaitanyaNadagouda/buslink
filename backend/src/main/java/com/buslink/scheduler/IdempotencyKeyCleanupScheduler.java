package com.buslink.scheduler;

import com.buslink.repository.IdempotencyKeyRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyKeyCleanupScheduler {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @Scheduled(fixedDelayString = "${idempotency.cleanup.interval-ms}")
    @Transactional
    public void cleanupExpiredKeys() {
        long deleted = idempotencyKeyRepository.deleteByExpiresAtBefore(Instant.now());
        log.info("IdempotencyKeyCleanupScheduler: purged {} expired idempotency key(s)", deleted);
    }
}
