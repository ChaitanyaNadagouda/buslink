package com.buslink.service.impl;

import com.buslink.config.TicketProperties;
import com.buslink.dto.request.IssueTicketRequestDTO;
import com.buslink.dto.response.IssueTicketResponseDTO;
import com.buslink.dto.response.PendingTicketResponseDTO;
import com.buslink.dto.response.TicketDetailResponseDTO;
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
import com.buslink.service.TicketService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final ConductorRepository conductorRepository;
    private final BusRepository busRepository;
    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final TicketProperties ticketProperties;

    @Override
    @Transactional
    public IssueTicketResponseDTO issueTicket(
            IssueTicketRequestDTO request, UUID conductorId, String idempotencyKey) {
        var existingKey = idempotencyKeyRepository.findByKey(idempotencyKey);
        if (existingKey.isPresent()) {
            IdempotencyKey key = existingKey.get();
            if (key.getExpiresAt().isAfter(Instant.now())) {
                Ticket existingTicket = ticketRepository
                        .findById(key.getTicketId())
                        .orElseThrow(() -> new ResourceNotFoundException("Ticket", "ticketId", key.getTicketId()));
                return toIssueResponseDTO(existingTicket);
            }
            idempotencyKeyRepository.delete(key);
        }

        User passenger = userRepository
                .findByQrToken(request.qrToken())
                .orElseThrow(() -> new ResourceNotFoundException("Passenger not found"));
        if (passenger.getStatus() != UserStatus.ACTIVE) {
            throw new ValidationException("Passenger account is not active");
        }

        Conductor conductor = conductorRepository
                .findById(conductorId)
                .orElseThrow(() -> new ResourceNotFoundException("Conductor", "conductorId", conductorId));
        if (!conductor.getBusId().equals(request.busId())) {
            throw new ValidationException("Bus mismatch");
        }

        Bus bus = busRepository
                .findById(request.busId())
                .orElseThrow(() -> new ResourceNotFoundException("Bus", "busId", request.busId()));
        if (!bus.getRouteId().equals(request.routeId())) {
            throw new ValidationException("Route mismatch");
        }

        RouteStop origin = findStopByName(request.routeId(), request.originStop());
        RouteStop destination = findStopByName(request.routeId(), request.destinationStop());
        if (destination.getStopSequence() <= origin.getStopSequence()) {
            throw new ValidationException("Destination must be after origin");
        }

        Route route = routeRepository
                .findById(request.routeId())
                .orElseThrow(() -> new ResourceNotFoundException("Route", "routeId", request.routeId()));

        int stagesCrossed = (destination.getStageNumber() - origin.getStageNumber()) + 1;
        BigDecimal adultFare = route.getFarePerStage().multiply(BigDecimal.valueOf(stagesCrossed));
        BigDecimal childFare = adultFare.divide(BigDecimal.valueOf(2), 2, RoundingMode.CEILING);
        BigDecimal totalFare = adultFare
                .multiply(BigDecimal.valueOf(request.adults()))
                .add(childFare.multiply(BigDecimal.valueOf(request.children())));

        Ticket ticket = Ticket.builder()
                .userId(passenger.getUserId())
                .conductorId(conductorId)
                .busId(request.busId())
                .routeId(request.routeId())
                .originStop(request.originStop())
                .destinationStop(request.destinationStop())
                .stagesCrossed(stagesCrossed)
                .adultCount(request.adults())
                .childCount(request.children())
                .infantCount(request.infants())
                .adultFare(adultFare)
                .childFare(childFare)
                .totalFare(totalFare)
                .status(TicketStatus.ISSUED)
                .issuedAt(Instant.now())
                .build();
        ticket = ticketRepository.save(ticket);

        Instant now = Instant.now();
        IdempotencyKey newKey = IdempotencyKey.builder()
                .key(idempotencyKey)
                .ticketId(ticket.getTicketId())
                .createdAt(now)
                .expiresAt(now.plus(ticketProperties.ttlHours(), ChronoUnit.HOURS))
                .build();
        idempotencyKeyRepository.save(newKey);

        return toIssueResponseDTO(ticket);
    }

    @Override
    public List<PendingTicketResponseDTO> getPendingTickets(UUID conductorId) {
        return ticketRepository
                .findByConductorIdAndStatusOrderByIssuedAtAsc(conductorId, TicketStatus.ISSUED)
                .stream()
                .map(this::toPendingResponseDTO)
                .toList();
    }

    @Override
    @Transactional
    public IssueTicketResponseDTO terminateTicket(UUID ticketId, UUID conductorId) {
        Ticket ticket = ticketRepository
                .findByTicketIdAndConductorId(ticketId, conductorId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "ticketId", ticketId));

        if (ticket.getStatus() != TicketStatus.ISSUED) {
            throw new ValidationException("Only ISSUED tickets can be terminated");
        }

        ticket.setStatus(TicketStatus.TERMINATED);
        ticket = ticketRepository.save(ticket);

        return toIssueResponseDTO(ticket);
    }

    @Override
    public List<TicketDetailResponseDTO> getPassengerTickets(UUID userId) {
        return ticketRepository.findByUserIdOrderByIssuedAtDesc(userId).stream()
                .map(this::toDetailResponseDTO)
                .toList();
    }

    @Override
    public TicketDetailResponseDTO getTicketById(UUID ticketId, UUID userId) {
        Ticket ticket = ticketRepository
                .findByTicketIdAndUserId(ticketId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket", "ticketId", ticketId));
        return toDetailResponseDTO(ticket);
    }

    private RouteStop findStopByName(UUID routeId, String stopName) {
        return routeStopRepository
                .findByRouteIdAndStopName(routeId, stopName)
                .orElseThrow(() -> new ResourceNotFoundException("RouteStop", "stopName", stopName));
    }

    private PendingTicketResponseDTO toPendingResponseDTO(Ticket ticket) {
        User passenger = userRepository
                .findById(ticket.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "userId", ticket.getUserId()));
        long minutesSinceIssue = ChronoUnit.MINUTES.between(ticket.getIssuedAt(), Instant.now());

        return new PendingTicketResponseDTO(
                ticket.getTicketId(),
                passenger.getName(),
                passenger.getQrToken(),
                ticket.getOriginStop(),
                ticket.getDestinationStop(),
                ticket.getTotalFare(),
                ticket.getStatus(),
                ticket.getIssuedAt(),
                minutesSinceIssue);
    }

    private TicketDetailResponseDTO toDetailResponseDTO(Ticket ticket) {
        Conductor conductor = conductorRepository
                .findById(ticket.getConductorId())
                .orElseThrow(() -> new ResourceNotFoundException("Conductor", "conductorId", ticket.getConductorId()));
        Bus bus = busRepository
                .findById(ticket.getBusId())
                .orElseThrow(() -> new ResourceNotFoundException("Bus", "busId", ticket.getBusId()));
        Route route = routeRepository
                .findById(ticket.getRouteId())
                .orElseThrow(() -> new ResourceNotFoundException("Route", "routeId", ticket.getRouteId()));

        return new TicketDetailResponseDTO(
                ticket.getTicketId(),
                conductor.getName(),
                bus.getBusNumber(),
                route.getRouteNumber(),
                ticket.getOriginStop(),
                ticket.getDestinationStop(),
                ticket.getStagesCrossed(),
                ticket.getAdultCount(),
                ticket.getChildCount(),
                ticket.getInfantCount(),
                ticket.getAdultFare(),
                ticket.getChildFare(),
                ticket.getTotalFare(),
                ticket.getStatus(),
                ticket.getIssuedAt(),
                ticket.getPaidAt());
    }

    private IssueTicketResponseDTO toIssueResponseDTO(Ticket ticket) {
        return new IssueTicketResponseDTO(
                ticket.getTicketId(),
                ticket.getUserId(),
                ticket.getOriginStop(),
                ticket.getDestinationStop(),
                ticket.getStagesCrossed(),
                ticket.getAdultCount(),
                ticket.getChildCount(),
                ticket.getInfantCount(),
                ticket.getAdultFare(),
                ticket.getChildFare(),
                ticket.getTotalFare(),
                ticket.getStatus(),
                ticket.getIssuedAt());
    }
}
