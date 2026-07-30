package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buslink.config.TicketProperties;
import com.buslink.dto.request.IssueTicketRequestDTO;
import com.buslink.dto.response.IssueTicketResponseDTO;
import com.buslink.entity.Bus;
import com.buslink.entity.Conductor;
import com.buslink.entity.IdempotencyKey;
import com.buslink.entity.Route;
import com.buslink.entity.RouteStop;
import com.buslink.entity.Ticket;
import com.buslink.entity.User;
import com.buslink.enums.TicketStatus;
import com.buslink.enums.UserStatus;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.exception.ValidationException;
import com.buslink.repository.BusRepository;
import com.buslink.repository.ConductorRepository;
import com.buslink.repository.IdempotencyKeyRepository;
import com.buslink.repository.RouteRepository;
import com.buslink.repository.RouteStopRepository;
import com.buslink.repository.TicketRepository;
import com.buslink.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TicketServiceImplTest {

    @Mock
    private TicketRepository ticketRepository;

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
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private TicketProperties ticketProperties;

    @InjectMocks
    private TicketServiceImpl ticketService;

    private final UUID conductorId = UUID.randomUUID();
    private final UUID busId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final String qrToken = "qr-token-abc";
    private final String idempotencyKey = "idem-key-1";

    private final IssueTicketRequestDTO request =
            new IssueTicketRequestDTO(qrToken, busId, routeId, "Stop A", "Stop C", 2, 1, 0);
    private final User passenger =
            User.builder().userId(userId).qrToken(qrToken).status(UserStatus.ACTIVE).build();
    private final Conductor conductor =
            Conductor.builder().conductorId(conductorId).busId(busId).build();
    private final Bus bus = Bus.builder().busId(busId).routeId(routeId).build();
    private final RouteStop origin =
            RouteStop.builder().routeId(routeId).stopName("Stop A").stopSequence(1).stageNumber(1).build();
    private final RouteStop destination =
            RouteStop.builder().routeId(routeId).stopName("Stop C").stopSequence(3).stageNumber(3).build();
    private final Route route =
            Route.builder().routeId(routeId).farePerStage(new BigDecimal("10.00")).build();

    @Test
    void issueTicket_success() {
        when(idempotencyKeyRepository.findByKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userRepository.findByQrToken(qrToken)).thenReturn(Optional.of(passenger));
        when(conductorRepository.findById(conductorId)).thenReturn(Optional.of(conductor));
        when(busRepository.findById(busId)).thenReturn(Optional.of(bus));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop A")).thenReturn(Optional.of(origin));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop C")).thenReturn(Optional.of(destination));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> {
            Ticket ticket = invocation.getArgument(0);
            ticket.setTicketId(ticketId);
            return ticket;
        });
        when(ticketProperties.ttlHours()).thenReturn(24L);

        IssueTicketResponseDTO response = ticketService.issueTicket(request, conductorId, idempotencyKey);

        assertThat(response.ticketId()).isEqualTo(ticketId);
        assertThat(response.status()).isEqualTo(TicketStatus.ISSUED);
        assertThat(response.stagesCrossed()).isEqualTo(3);
        assertThat(response.adultFare()).isEqualByComparingTo("30.00");
        assertThat(response.childFare()).isEqualByComparingTo("15.00");
        assertThat(response.totalFare()).isEqualByComparingTo("75.00");

        ArgumentCaptor<IdempotencyKey> keyCaptor = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(idempotencyKeyRepository).save(keyCaptor.capture());
        assertThat(keyCaptor.getValue().getKey()).isEqualTo(idempotencyKey);
        assertThat(keyCaptor.getValue().getTicketId()).isEqualTo(ticketId);
    }

    @Test
    void issueTicket_idempotentRequest() {
        Ticket existingTicket = Ticket.builder()
                .ticketId(ticketId)
                .userId(userId)
                .originStop("Stop A")
                .destinationStop("Stop C")
                .stagesCrossed(3)
                .adultCount(2)
                .childCount(1)
                .infantCount(0)
                .adultFare(new BigDecimal("30.00"))
                .childFare(new BigDecimal("15.00"))
                .totalFare(new BigDecimal("75.00"))
                .status(TicketStatus.ISSUED)
                .issuedAt(Instant.now())
                .build();
        IdempotencyKey existingKey = IdempotencyKey.builder()
                .key(idempotencyKey)
                .ticketId(ticketId)
                .createdAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        when(idempotencyKeyRepository.findByKey(idempotencyKey)).thenReturn(Optional.of(existingKey));
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(existingTicket));

        IssueTicketResponseDTO response = ticketService.issueTicket(request, conductorId, idempotencyKey);

        assertThat(response.ticketId()).isEqualTo(ticketId);
        assertThat(response.totalFare()).isEqualByComparingTo("75.00");
        verify(ticketRepository, never()).save(any(Ticket.class));
        verify(idempotencyKeyRepository, never()).save(any(IdempotencyKey.class));
    }

    @Test
    void issueTicket_invalidQrToken() {
        when(idempotencyKeyRepository.findByKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userRepository.findByQrToken(qrToken)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> ticketService.issueTicket(request, conductorId, idempotencyKey))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void issueTicket_inactivePassenger() {
        User inactivePassenger =
                User.builder().userId(userId).qrToken(qrToken).status(UserStatus.SUSPENDED).build();
        when(idempotencyKeyRepository.findByKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userRepository.findByQrToken(qrToken)).thenReturn(Optional.of(inactivePassenger));

        assertThatThrownBy(() -> ticketService.issueTicket(request, conductorId, idempotencyKey))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void issueTicket_busMismatch() {
        Conductor mismatchedConductor =
                Conductor.builder().conductorId(conductorId).busId(UUID.randomUUID()).build();
        when(idempotencyKeyRepository.findByKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userRepository.findByQrToken(qrToken)).thenReturn(Optional.of(passenger));
        when(conductorRepository.findById(conductorId)).thenReturn(Optional.of(mismatchedConductor));

        assertThatThrownBy(() -> ticketService.issueTicket(request, conductorId, idempotencyKey))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void issueTicket_destinationBeforeOrigin() {
        RouteStop reversedDestination =
                RouteStop.builder().routeId(routeId).stopName("Stop C").stopSequence(0).stageNumber(0).build();
        when(idempotencyKeyRepository.findByKey(idempotencyKey)).thenReturn(Optional.empty());
        when(userRepository.findByQrToken(qrToken)).thenReturn(Optional.of(passenger));
        when(conductorRepository.findById(conductorId)).thenReturn(Optional.of(conductor));
        when(busRepository.findById(busId)).thenReturn(Optional.of(bus));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop A")).thenReturn(Optional.of(origin));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop C"))
                .thenReturn(Optional.of(reversedDestination));

        assertThatThrownBy(() -> ticketService.issueTicket(request, conductorId, idempotencyKey))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void terminateTicket_success() {
        Ticket ticket =
                Ticket.builder().ticketId(ticketId).conductorId(conductorId).status(TicketStatus.ISSUED).build();
        when(ticketRepository.findByTicketIdAndConductorId(ticketId, conductorId)).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(ticket)).thenReturn(ticket);

        IssueTicketResponseDTO response = ticketService.terminateTicket(ticketId, conductorId);

        assertThat(response.status()).isEqualTo(TicketStatus.TERMINATED);
    }

    @Test
    void terminateTicket_alreadyPaid() {
        Ticket ticket =
                Ticket.builder().ticketId(ticketId).conductorId(conductorId).status(TicketStatus.PAID).build();
        when(ticketRepository.findByTicketIdAndConductorId(ticketId, conductorId)).thenReturn(Optional.of(ticket));

        assertThatThrownBy(() -> ticketService.terminateTicket(ticketId, conductorId))
                .isInstanceOf(ValidationException.class);
    }
}
