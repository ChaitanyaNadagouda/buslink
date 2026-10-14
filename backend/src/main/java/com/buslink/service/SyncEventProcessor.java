package com.buslink.service;

import com.buslink.config.SyncProperties;
import com.buslink.config.WalletProperties;
import com.buslink.dto.request.SyncEventRequestDTO;
import com.buslink.dto.response.SyncEventResultDTO;
import com.buslink.entity.Bus;
import com.buslink.entity.Conductor;
import com.buslink.entity.Route;
import com.buslink.entity.RouteStop;
import com.buslink.entity.SyncEvent;
import com.buslink.entity.Ticket;
import com.buslink.entity.Transaction;
import com.buslink.entity.User;
import com.buslink.entity.Wallet;
import com.buslink.enums.SyncStatus;
import com.buslink.enums.TicketStatus;
import com.buslink.enums.TransactionStatus;
import com.buslink.enums.TransactionType;
import com.buslink.enums.UserStatus;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.exception.ValidationException;
import com.buslink.repository.BusRepository;
import com.buslink.repository.ConductorRepository;
import com.buslink.repository.RouteRepository;
import com.buslink.repository.RouteStopRepository;
import com.buslink.repository.SyncEventRepository;
import com.buslink.repository.TicketRepository;
import com.buslink.repository.TransactionRepository;
import com.buslink.repository.UserRepository;
import com.buslink.repository.WalletRepository;
import com.buslink.security.JwtUtil;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate {@code @Service} bean, NOT an inner class of {@code SyncServiceImpl} — {@code @Transactional}
 * per event requires its own Spring AOP proxy. Self-invocation from the same bean would bypass the proxy
 * entirely (same lesson as Sprint 6's {@code @Cacheable} self-invocation issue), and one event's failure
 * must never roll back events already committed earlier in the same batch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyncEventProcessor {

    private final SyncEventRepository syncEventRepository;
    private final UserRepository userRepository;
    private final ConductorRepository conductorRepository;
    private final BusRepository busRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final WalletRepository walletRepository;
    private final TicketRepository ticketRepository;
    private final TransactionRepository transactionRepository;
    private final WalletProperties walletProperties;
    private final SyncProperties syncProperties;
    private final JwtUtil jwtUtil;

    @Transactional
    public SyncEventResultDTO processEvent(SyncEventRequestDTO event, UUID conductorId) {
        SyncEvent syncEvent = findOrInitSyncEvent(event, conductorId);
        if (isFinal(syncEvent.getStatus())) {
            return toResultDTO(syncEvent);
        }

        syncEvent.setStatus(SyncStatus.PROCESSING);
        // saveAndFlush, not save: for a brand-new event this sends the INSERT now, so a concurrent request
        // for the same eventId hits the primary key HERE — waiting for us, then failing with duplicate key —
        // before doing any work or touching the wallet. With plain save() the INSERT would be deferred to
        // commit, after all the work. The duplicate-key exception propagates out of this method (Postgres has
        // aborted the transaction, so nothing more can run in it) and is caught per event by the caller's loop.
        syncEvent = syncEventRepository.saveAndFlush(syncEvent);

        try {
            return resolve(event, conductorId, syncEvent);
        } catch (Exception ex) {
            log.error("Sync event {} failed with an unexpected exception", event.eventId(), ex);
            return fail(syncEvent, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    /**
     * Idempotency check + find-or-create collapsed into one lookup. {@code SyncEvent} rows are permanent
     * (nothing ever deletes them), so this alone is a complete, permanent idempotency guard — a separate
     * {@code IdempotencyKey} check (as used for ticket issuance) would only add a weaker, TTL-bound
     * duplicate of what this already does; see Sprint-07.md's deviation note.
     *
     * <p>Safe under concurrency: an existing row is read with {@code SELECT ... FOR UPDATE}, so a second
     * request for the same event waits for the first to commit and then sees its final status. A missing row
     * can't be locked; there, the freshly built entity has {@code isNew() == true}, so the save is a plain
     * INSERT and the primary key rejects the loser. See docs/SYNC_RACE_CONDITIONS.md.
     */
    private SyncEvent findOrInitSyncEvent(SyncEventRequestDTO event, UUID conductorId) {
        SyncEvent syncEvent = syncEventRepository
                .findByIdForUpdate(event.eventId())
                .orElseGet(() -> SyncEvent.builder()
                        .eventId(event.eventId())
                        .status(SyncStatus.PENDING)
                        .build());

        if (isFinal(syncEvent.getStatus())) {
            return syncEvent;
        }

        // FAILED (retry) or freshly created — (re)populate from the latest request before reprocessing.
        syncEvent.setConductorId(conductorId);
        syncEvent.setUserId(event.userId());
        syncEvent.setPassengerQrToken(event.passengerQrToken());
        syncEvent.setBusId(event.busId());
        syncEvent.setRouteId(event.routeId());
        syncEvent.setOriginStop(event.originStop());
        syncEvent.setDestinationStop(event.destinationStop());
        syncEvent.setIssuedAt(event.issuedAt());
        syncEvent.setClientFare(event.clientFare());
        return syncEvent;
    }

    private SyncEventResultDTO resolve(SyncEventRequestDTO event, UUID conductorId, SyncEvent syncEvent) {
        if (!conductorId.equals(event.conductorId())) {
            return reject(syncEvent, "Conductor mismatch");
        }

        Instant now = Instant.now();
        Duration clockSkew = Duration.ofMinutes(syncProperties.clockSkewToleranceMinutes());
        Instant oldestAllowed = now.minus(syncProperties.maxSyncAgeDays(), ChronoUnit.DAYS);
        if (event.issuedAt().isBefore(oldestAllowed)) {
            return reject(syncEvent, "Event too old — possible replay or clock manipulation");
        }
        // A real ticket can't be issued in the future; beyond normal phone-clock drift this means a
        // wrong/tampered device clock, which would otherwise slip past the too-old check above.
        if (event.issuedAt().isAfter(now.plus(clockSkew))) {
            return reject(syncEvent, "Event issued in the future — device clock is wrong");
        }

        Conductor conductor = conductorRepository
                .findById(conductorId)
                .orElseThrow(() -> new ResourceNotFoundException("Conductor", "conductorId", conductorId));
        if (!conductor.getBusId().equals(event.busId())) {
            return reject(syncEvent, "Bus mismatch");
        }

        Bus bus = busRepository
                .findById(event.busId())
                .orElseThrow(() -> new ResourceNotFoundException("Bus", "busId", event.busId()));
        if (!bus.getRouteId().equals(event.routeId())) {
            return reject(syncEvent, "Route mismatch");
        }

        UUID qrUserId;
        try {
            // Valid at BOARDING time (event.issuedAt), not now — the sync may arrive long after the QR's
            // 24h exp, but what matters is that the passenger showed a valid QR when they boarded.
            qrUserId = jwtUtil.extractUserIdFromPassengerQrAt(
                    event.passengerQrToken(), event.issuedAt(), clockSkew);
        } catch (ValidationException ex) {
            return reject(syncEvent, "Invalid QR token");
        }
        if (!qrUserId.equals(event.userId())) {
            return reject(syncEvent, "Invalid QR token");
        }
        User passenger = userRepository.findById(qrUserId).orElse(null);
        if (passenger == null) {
            return reject(syncEvent, "Invalid QR token");
        }
        if (passenger.getStatus() != UserStatus.ACTIVE) {
            return reject(syncEvent, "Passenger inactive");
        }

        RouteStop origin = routeStopRepository
                .findByRouteIdAndStopName(event.routeId(), event.originStop())
                .orElse(null);
        RouteStop destination = routeStopRepository
                .findByRouteIdAndStopName(event.routeId(), event.destinationStop())
                .orElse(null);
        if (origin == null || destination == null) {
            return reject(syncEvent, "Invalid stop");
        }
        if (destination.getStopSequence() <= origin.getStopSequence()) {
            return reject(syncEvent, "Invalid direction");
        }

        Route route = routeRepository
                .findById(event.routeId())
                .orElseThrow(() -> new ResourceNotFoundException("Route", "routeId", event.routeId()));

        // NEVER trust event.clientFare() — it's logged on the entity only, recalculated here.
        // No adults/children/infants breakdown exists for offline events (SyncEventRequestDTO carries
        // none, matching the single-`fare`-field ticket QR payload from S7-04) — one adult fare, always.
        int stagesCrossed = (destination.getStageNumber() - origin.getStageNumber()) + 1;
        BigDecimal adultFare = route.getFarePerStage().multiply(BigDecimal.valueOf(stagesCrossed));
        BigDecimal totalFare = adultFare;

        Wallet wallet = walletRepository
                .findByUserId(qrUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet", "userId", qrUserId));
        BigDecimal effectiveBalance = wallet.getBalance().add(walletProperties.overdraftLimit());

        Ticket ticket = Ticket.builder()
                .userId(qrUserId)
                .conductorId(conductorId)
                .busId(event.busId())
                .routeId(event.routeId())
                .originStop(event.originStop())
                .destinationStop(event.destinationStop())
                .stagesCrossed(stagesCrossed)
                .adultCount(1)
                .childCount(0)
                .infantCount(0)
                .adultFare(adultFare)
                .childFare(BigDecimal.ZERO)
                .totalFare(totalFare)
                .issuedAt(event.issuedAt())
                .build();

        if (effectiveBalance.compareTo(totalFare) >= 0) {
            wallet.setBalance(wallet.getBalance().subtract(totalFare));
            walletRepository.save(wallet);

            transactionRepository.save(Transaction.builder()
                    .userId(qrUserId)
                    .amount(totalFare)
                    .type(TransactionType.DEBIT)
                    .status(TransactionStatus.SUCCESS)
                    .referenceId(event.eventId())
                    .build());

            ticket.setStatus(TicketStatus.PAID);
            ticket.setPaidAt(Instant.now());
            ticket = ticketRepository.save(ticket);

            return succeed(syncEvent, SyncStatus.SYNCED, ticket.getTicketId(), "Ticket issued and paid");
        }

        ticket.setStatus(TicketStatus.DISPUTED);
        ticket = ticketRepository.save(ticket);
        return succeed(syncEvent, SyncStatus.DISPUTED, ticket.getTicketId(), "Wallet insufficient — ticket disputed");
    }

    private SyncEventResultDTO succeed(SyncEvent syncEvent, SyncStatus status, UUID ticketId, String message) {
        syncEvent.setStatus(status);
        syncEvent.setResultTicketId(ticketId);
        syncEvent.setErrorMessage(null);
        syncEventRepository.save(syncEvent);
        return new SyncEventResultDTO(syncEvent.getEventId(), status, ticketId, message);
    }

    /** Technical failure: retryable (scheduler while under the attempt cap, or a re-upload). */
    private SyncEventResultDTO fail(SyncEvent syncEvent, String message) {
        syncEvent.setStatus(SyncStatus.FAILED);
        syncEvent.setErrorMessage(message);
        syncEvent.setFailedAttempts(syncEvent.getFailedAttempts() + 1);
        syncEventRepository.save(syncEvent);
        return new SyncEventResultDTO(syncEvent.getEventId(), SyncStatus.FAILED, null, message);
    }

    /** The event's own data is invalid: final, never retried — reprocessing the same data gives the same answer. */
    private SyncEventResultDTO reject(SyncEvent syncEvent, String message) {
        syncEvent.setStatus(SyncStatus.REJECTED);
        syncEvent.setErrorMessage(message);
        syncEventRepository.save(syncEvent);
        return new SyncEventResultDTO(syncEvent.getEventId(), SyncStatus.REJECTED, null, message);
    }

    private SyncEventResultDTO toResultDTO(SyncEvent syncEvent) {
        String message = switch (syncEvent.getStatus()) {
            case SYNCED -> "Ticket issued and paid";
            case DISPUTED -> "Wallet insufficient — ticket disputed";
            default -> syncEvent.getErrorMessage(); // REJECTED: the stored reason
        };
        return new SyncEventResultDTO(
                syncEvent.getEventId(), syncEvent.getStatus(), syncEvent.getResultTicketId(), message);
    }

    private static boolean isFinal(SyncStatus status) {
        return status == SyncStatus.SYNCED || status == SyncStatus.DISPUTED || status == SyncStatus.REJECTED;
    }
}
