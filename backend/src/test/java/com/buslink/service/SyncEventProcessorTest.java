package com.buslink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buslink.config.SyncProperties;
import com.buslink.config.WalletProperties;
import com.buslink.dto.request.SyncBatchRequestDTO;
import com.buslink.dto.request.SyncEventRequestDTO;
import com.buslink.dto.response.SyncBatchResponseDTO;
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
import com.buslink.enums.TransactionStatus;
import com.buslink.enums.TransactionType;
import com.buslink.enums.UserStatus;
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
import com.buslink.service.impl.SyncServiceImpl;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SyncEventProcessorTest {

    @Mock
    private SyncEventRepository syncEventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ConductorRepository conductorRepository;

    @Mock
    private BusRepository busRepository;

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private RouteStopRepository routeStopRepository;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private WalletProperties walletProperties;

    @Mock
    private SyncProperties syncProperties;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private SyncEventProcessor syncEventProcessor;

    private final UUID eventId = UUID.randomUUID();
    private final UUID conductorId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID busId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private final String qrToken = "passenger-qr-token";
    private final Instant issuedAt = Instant.now();

    // Stage 1 -> 9 at ₹10/stage = ₹90 totalFare, matching the plan's own worked examples.
    private final SyncEventRequestDTO event = new SyncEventRequestDTO(
            eventId, conductorId, userId, qrToken, busId, routeId, "Stop A", "Stop I", issuedAt, null);
    private final Conductor conductor = Conductor.builder().conductorId(conductorId).busId(busId).build();
    private final Bus bus = Bus.builder().busId(busId).routeId(routeId).build();
    private final RouteStop origin =
            RouteStop.builder().routeId(routeId).stopName("Stop A").stopSequence(1).stageNumber(1).build();
    private final RouteStop destination =
            RouteStop.builder().routeId(routeId).stopName("Stop I").stopSequence(9).stageNumber(9).build();
    private final Route route = Route.builder().routeId(routeId).farePerStage(new BigDecimal("10.00")).build();
    private final User passenger =
            User.builder().userId(userId).status(UserStatus.ACTIVE).build();

    private void stubHappyPathValidationChain() {
        when(syncProperties.maxSyncAgeDays()).thenReturn(7);
        when(syncProperties.clockSkewToleranceMinutes()).thenReturn(5);
        when(conductorRepository.findById(conductorId)).thenReturn(Optional.of(conductor));
        when(busRepository.findById(busId)).thenReturn(Optional.of(bus));
        when(jwtUtil.extractUserIdFromPassengerQrAt(eq(qrToken), any(Instant.class), any(Duration.class))).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(passenger));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop A")).thenReturn(Optional.of(origin));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop I")).thenReturn(Optional.of(destination));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(syncEventRepository.save(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncEventRepository.saveAndFlush(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket ticket = inv.getArgument(0);
            ticket.setTicketId(ticketId);
            return ticket;
        });
    }

    @Test
    void processEvent_success_sufficientBalance() {
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());
        stubHappyPathValidationChain();
        Wallet wallet = Wallet.builder().userId(userId).balance(new BigDecimal("150.00")).build();
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletProperties.overdraftLimit()).thenReturn(new BigDecimal("100.00"));
        when(walletRepository.save(wallet)).thenReturn(wallet);

        SyncEventResultDTO result = syncEventProcessor.processEvent(event, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.SYNCED);
        assertThat(result.ticketId()).isEqualTo(ticketId);
        assertThat(result.message()).isEqualTo("Ticket issued and paid");
        assertThat(wallet.getBalance()).isEqualByComparingTo("60.00");

        ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(txCaptor.capture());
        assertThat(txCaptor.getValue().getType()).isEqualTo(TransactionType.DEBIT);
        assertThat(txCaptor.getValue().getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(txCaptor.getValue().getAmount()).isEqualByComparingTo("90.00");
        assertThat(txCaptor.getValue().getReferenceId()).isEqualTo(eventId);
    }

    @Test
    void processEvent_success_overdraftAllowed() {
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());
        stubHappyPathValidationChain();
        Wallet wallet = Wallet.builder().userId(userId).balance(new BigDecimal("20.00")).build();
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletProperties.overdraftLimit()).thenReturn(new BigDecimal("100.00"));
        when(walletRepository.save(wallet)).thenReturn(wallet);

        SyncEventResultDTO result = syncEventProcessor.processEvent(event, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.SYNCED);
        assertThat(wallet.getBalance()).isEqualByComparingTo("-70.00");
    }

    @Test
    void processEvent_disputed_exceedsOverdraft() {
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());
        stubHappyPathValidationChain();
        Wallet wallet = Wallet.builder().userId(userId).balance(new BigDecimal("-80.00")).build();
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletProperties.overdraftLimit()).thenReturn(new BigDecimal("100.00"));

        SyncEventResultDTO result = syncEventProcessor.processEvent(event, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.DISPUTED);
        assertThat(result.message()).isEqualTo("Wallet insufficient — ticket disputed");
        assertThat(wallet.getBalance()).isEqualByComparingTo("-80.00");
        verify(walletRepository, never()).save(any(Wallet.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void processEvent_idempotent_alreadySynced() {
        SyncEvent existing = SyncEvent.builder()
                .eventId(eventId)
                .status(SyncStatus.SYNCED)
                .resultTicketId(ticketId)
                .build();
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(existing));

        SyncEventResultDTO result = syncEventProcessor.processEvent(event, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.SYNCED);
        assertThat(result.ticketId()).isEqualTo(ticketId);
        verify(conductorRepository, never()).findById(any());
        verify(walletRepository, never()).findByUserId(any());
        verify(syncEventRepository, never()).save(any(SyncEvent.class));
        verify(syncEventRepository, never()).saveAndFlush(any(SyncEvent.class));
    }

    @Test
    void processEvent_rejected_invalidPassengerQr() {
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());
        when(syncProperties.maxSyncAgeDays()).thenReturn(7);
        when(syncProperties.clockSkewToleranceMinutes()).thenReturn(5);
        when(conductorRepository.findById(conductorId)).thenReturn(Optional.of(conductor));
        when(busRepository.findById(busId)).thenReturn(Optional.of(bus));
        when(jwtUtil.extractUserIdFromPassengerQrAt(eq(qrToken), any(Instant.class), any(Duration.class)))
                .thenThrow(new ValidationException("Invalid or expired passenger QR token"));
        when(syncEventRepository.save(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncEventRepository.saveAndFlush(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        SyncEventResultDTO result = syncEventProcessor.processEvent(event, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.REJECTED);
        assertThat(result.message()).isEqualTo("Invalid QR token");
    }

    @Test
    void processEvent_rejected_busMismatch() {
        Conductor mismatchedConductor =
                Conductor.builder().conductorId(conductorId).busId(UUID.randomUUID()).build();
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());
        when(syncProperties.maxSyncAgeDays()).thenReturn(7);
        when(syncProperties.clockSkewToleranceMinutes()).thenReturn(5);
        when(conductorRepository.findById(conductorId)).thenReturn(Optional.of(mismatchedConductor));
        when(syncEventRepository.save(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncEventRepository.saveAndFlush(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        SyncEventResultDTO result = syncEventProcessor.processEvent(event, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.REJECTED);
        assertThat(result.message()).isEqualTo("Bus mismatch");
        verify(busRepository, never()).findById(any());
    }

    @Test
    void processEvent_rejected_tooOld() {
        SyncEventRequestDTO oldEvent = new SyncEventRequestDTO(
                eventId,
                conductorId,
                userId,
                qrToken,
                busId,
                routeId,
                "Stop A",
                "Stop I",
                Instant.now().minus(10, ChronoUnit.DAYS),
                null);
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());
        when(syncProperties.maxSyncAgeDays()).thenReturn(7);
        when(syncProperties.clockSkewToleranceMinutes()).thenReturn(5);
        when(syncEventRepository.save(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncEventRepository.saveAndFlush(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        SyncEventResultDTO result = syncEventProcessor.processEvent(oldEvent, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.REJECTED);
        assertThat(result.message()).isEqualTo("Event too old — possible replay or clock manipulation");
        verify(conductorRepository, never()).findById(any());
    }

    @Test
    void processEvent_rejected_issuedInFuture() {
        SyncEventRequestDTO futureEvent = new SyncEventRequestDTO(
                eventId,
                conductorId,
                userId,
                qrToken,
                busId,
                routeId,
                "Stop A",
                "Stop I",
                Instant.now().plus(1, ChronoUnit.HOURS), // beyond the 5-min clock-skew tolerance
                null);
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());
        when(syncProperties.maxSyncAgeDays()).thenReturn(7);
        when(syncProperties.clockSkewToleranceMinutes()).thenReturn(5);
        when(syncEventRepository.save(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncEventRepository.saveAndFlush(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        SyncEventResultDTO result = syncEventProcessor.processEvent(futureEvent, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.REJECTED);
        assertThat(result.message()).isEqualTo("Event issued in the future — device clock is wrong");
        verify(conductorRepository, never()).findById(any());
    }

    @Test
    void processEvent_retryAfterFailed() {
        SyncEvent previouslyFailed = SyncEvent.builder()
                .eventId(eventId)
                .conductorId(conductorId)
                .userId(userId)
                .passengerQrToken(qrToken)
                .busId(busId)
                .routeId(routeId)
                .originStop("Stop A")
                .destinationStop("Stop I")
                .issuedAt(issuedAt)
                .status(SyncStatus.FAILED)
                .errorMessage("Bus mismatch")
                .build();
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(previouslyFailed));
        stubHappyPathValidationChain();
        Wallet wallet = Wallet.builder().userId(userId).balance(new BigDecimal("150.00")).build();
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));
        when(walletProperties.overdraftLimit()).thenReturn(new BigDecimal("100.00"));
        when(walletRepository.save(wallet)).thenReturn(wallet);

        SyncEventResultDTO result = syncEventProcessor.processEvent(event, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.SYNCED);
        assertThat(result.ticketId()).isEqualTo(ticketId);
    }

    @Test
    void processEvent_idempotent_alreadyRejected_returnsStoredReasonWithoutRevalidating() {
        SyncEvent rejected = SyncEvent.builder()
                .eventId(eventId)
                .status(SyncStatus.REJECTED)
                .errorMessage("Event too old — possible replay or clock manipulation")
                .build();
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(rejected));

        SyncEventResultDTO result = syncEventProcessor.processEvent(event, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.REJECTED);
        assertThat(result.message()).isEqualTo("Event too old — possible replay or clock manipulation");
        assertThat(result.ticketId()).isNull();
        verify(conductorRepository, never()).findById(any());
        verify(syncEventRepository, never()).saveAndFlush(any(SyncEvent.class));
        verify(syncEventRepository, never()).save(any(SyncEvent.class));
    }

    @Test
    void processEvent_failed_unexpectedError_staysRetryableAndCountsAttempt() {
        SyncEvent previouslyFailed = SyncEvent.builder()
                .eventId(eventId)
                .status(SyncStatus.FAILED)
                .failedAttempts(2)
                .build();
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(previouslyFailed));
        when(syncProperties.maxSyncAgeDays()).thenReturn(7);
        when(syncProperties.clockSkewToleranceMinutes()).thenReturn(5);
        // Technical, not a data problem: the conductor row can't be loaded → caught by the catch-all.
        when(conductorRepository.findById(conductorId)).thenReturn(Optional.empty());
        when(syncEventRepository.save(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncEventRepository.saveAndFlush(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        SyncEventResultDTO result = syncEventProcessor.processEvent(event, conductorId);

        assertThat(result.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(previouslyFailed.getStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(previouslyFailed.getFailedAttempts()).isEqualTo(3);
    }

    @Test
    void processEvent_rejected_doesNotCountAsFailedAttempt() {
        SyncEventRequestDTO oldEvent = new SyncEventRequestDTO(
                eventId, conductorId, userId, qrToken, busId, routeId, "Stop A", "Stop I",
                Instant.now().minus(10, ChronoUnit.DAYS), null);
        when(syncEventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());
        when(syncProperties.maxSyncAgeDays()).thenReturn(7);
        when(syncProperties.clockSkewToleranceMinutes()).thenReturn(5);
        when(syncEventRepository.save(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncEventRepository.saveAndFlush(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        syncEventProcessor.processEvent(oldEvent, conductorId);

        ArgumentCaptor<SyncEvent> saved = ArgumentCaptor.forClass(SyncEvent.class);
        verify(syncEventRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SyncStatus.REJECTED);
        assertThat(saved.getValue().getFailedAttempts()).isZero();
    }

    @Test
    void processEvent_partialBatch() {
        SyncServiceImpl syncServiceImpl = new SyncServiceImpl(syncEventProcessor, syncEventRepository);

        // Event A — succeeds.
        UUID eventIdA = UUID.randomUUID();
        UUID userIdA = UUID.randomUUID();
        String qrTokenA = "qr-a";
        SyncEventRequestDTO eventA = new SyncEventRequestDTO(
                eventIdA, conductorId, userIdA, qrTokenA, busId, routeId, "Stop A", "Stop I", issuedAt, null);
        User passengerA = User.builder().userId(userIdA).status(UserStatus.ACTIVE).build();
        Wallet walletA = Wallet.builder().userId(userIdA).balance(new BigDecimal("150.00")).build();
        when(jwtUtil.extractUserIdFromPassengerQrAt(eq(qrTokenA), any(Instant.class), any(Duration.class))).thenReturn(userIdA);
        when(userRepository.findById(userIdA)).thenReturn(Optional.of(passengerA));
        when(walletRepository.findByUserId(userIdA)).thenReturn(Optional.of(walletA));
        when(walletRepository.save(walletA)).thenReturn(walletA);

        // Event B — disputed (wallet exceeds overdraft).
        UUID eventIdB = UUID.randomUUID();
        UUID userIdB = UUID.randomUUID();
        String qrTokenB = "qr-b";
        SyncEventRequestDTO eventB = new SyncEventRequestDTO(
                eventIdB, conductorId, userIdB, qrTokenB, busId, routeId, "Stop A", "Stop I", issuedAt, null);
        User passengerB = User.builder().userId(userIdB).status(UserStatus.ACTIVE).build();
        Wallet walletB = Wallet.builder().userId(userIdB).balance(new BigDecimal("-80.00")).build();
        when(jwtUtil.extractUserIdFromPassengerQrAt(eq(qrTokenB), any(Instant.class), any(Duration.class))).thenReturn(userIdB);
        when(userRepository.findById(userIdB)).thenReturn(Optional.of(passengerB));
        when(walletRepository.findByUserId(userIdB)).thenReturn(Optional.of(walletB));

        // Event C — rejected (bus mismatch: bad data, never retried).
        UUID eventIdC = UUID.randomUUID();
        UUID wrongBusId = UUID.randomUUID();
        SyncEventRequestDTO eventC = new SyncEventRequestDTO(
                eventIdC, conductorId, userId, qrToken, wrongBusId, routeId, "Stop A", "Stop I", issuedAt, null);

        when(syncEventRepository.findByIdForUpdate(any(UUID.class))).thenReturn(Optional.empty());
        when(syncProperties.maxSyncAgeDays()).thenReturn(7);
        when(syncProperties.clockSkewToleranceMinutes()).thenReturn(5);
        when(conductorRepository.findById(conductorId)).thenReturn(Optional.of(conductor));
        when(busRepository.findById(busId)).thenReturn(Optional.of(bus));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop A")).thenReturn(Optional.of(origin));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop I")).thenReturn(Optional.of(destination));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(walletProperties.overdraftLimit()).thenReturn(new BigDecimal("100.00"));
        when(syncEventRepository.save(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(syncEventRepository.saveAndFlush(any(SyncEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(inv -> {
            Ticket ticket = inv.getArgument(0);
            ticket.setTicketId(UUID.randomUUID());
            return ticket;
        });

        SyncBatchRequestDTO batch = new SyncBatchRequestDTO(List.of(eventA, eventB, eventC));
        SyncBatchResponseDTO response = syncServiceImpl.processSyncBatch(batch, conductorId);

        assertThat(response.totalEvents()).isEqualTo(3);
        assertThat(response.synced()).isEqualTo(1);
        assertThat(response.disputed()).isEqualTo(1);
        assertThat(response.rejected()).isEqualTo(1);
        assertThat(response.failed()).isZero();
        assertThat(response.results()).hasSize(3);
    }
}
