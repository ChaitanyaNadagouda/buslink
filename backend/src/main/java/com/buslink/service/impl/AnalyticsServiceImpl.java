package com.buslink.service.impl;

import com.buslink.dto.response.ConductorActivityDTO;
import com.buslink.dto.response.RevenueByRouteDTO;
import com.buslink.dto.response.TicketsPerDayDTO;
import com.buslink.dto.response.TopRouteDTO;
import com.buslink.entity.Conductor;
import com.buslink.entity.Route;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.repository.ConductorRepository;
import com.buslink.repository.RouteRepository;
import com.buslink.repository.TicketRepository;
import com.buslink.service.AnalyticsService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private final TicketRepository ticketRepository;
    private final RouteRepository routeRepository;
    private final ConductorRepository conductorRepository;

    @Override
    public List<RevenueByRouteDTO> getRevenueByRoute() {
        return ticketRepository.findRevenueByRoute().stream()
                .map(row -> {
                    UUID routeId = (UUID) row[0];
                    BigDecimal totalRevenue = (BigDecimal) row[1];
                    return new RevenueByRouteDTO(routeId, findRouteName(routeId), totalRevenue);
                })
                .toList();
    }

    @Override
    public List<TicketsPerDayDTO> getTicketsPerDay() {
        return ticketRepository.findTicketsPerDay().stream()
                .map(row -> new TicketsPerDayDTO(toLocalDate(row[0]), (Long) row[1]))
                .toList();
    }

    @Override
    public List<TopRouteDTO> getTopRoutes() {
        return ticketRepository.findTopRoutesByVolume().stream()
                .map(row -> {
                    UUID routeId = (UUID) row[0];
                    Long ticketCount = (Long) row[1];
                    return new TopRouteDTO(routeId, findRouteName(routeId), ticketCount);
                })
                .toList();
    }

    @Override
    public List<ConductorActivityDTO> getConductorActivity() {
        return ticketRepository.findTicketsPerConductor().stream()
                .map(row -> {
                    UUID conductorId = (UUID) row[0];
                    Long ticketsIssued = (Long) row[1];
                    return new ConductorActivityDTO(conductorId, findConductorName(conductorId), ticketsIssued);
                })
                .toList();
    }

    private String findRouteName(UUID routeId) {
        Route route = routeRepository
                .findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route", "routeId", routeId));
        return route.getRouteName();
    }

    private String findConductorName(UUID conductorId) {
        Conductor conductor = conductorRepository
                .findById(conductorId)
                .orElseThrow(() -> new ResourceNotFoundException("Conductor", "conductorId", conductorId));
        return conductor.getName();
    }

    /**
     * {@code CAST(t.issuedAt AS date)} in the JPQL query can come back as either
     * {@code java.time.LocalDate} or {@code java.sql.Date} depending on the JDBC driver/Hibernate
     * type resolution for an Object[] projection — handled defensively rather than assumed,
     * verified against which branch actually fires during live testing.
     */
    private LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        throw new IllegalStateException(
                "Unexpected date type from CAST(issuedAt AS date): " + value.getClass());
    }
}
