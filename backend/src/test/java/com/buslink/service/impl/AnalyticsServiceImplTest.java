package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.buslink.dto.response.ConductorActivityDTO;
import com.buslink.dto.response.RevenueByRouteDTO;
import com.buslink.dto.response.TicketsPerDayDTO;
import com.buslink.dto.response.TopRouteDTO;
import com.buslink.entity.Conductor;
import com.buslink.entity.Route;
import com.buslink.repository.ConductorRepository;
import com.buslink.repository.RouteRepository;
import com.buslink.repository.TicketRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceImplTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private ConductorRepository conductorRepository;

    @InjectMocks
    private AnalyticsServiceImpl analyticsService;

    @Test
    void getRevenueByRoute_returnsCorrectTotals() {
        UUID routeId = UUID.randomUUID();
        Route route = Route.builder().routeId(routeId).routeName("Route 500K").build();

        when(ticketRepository.findRevenueByRoute())
                .thenReturn(List.<Object[]>of(new Object[] {routeId, new BigDecimal("450.00")}));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));

        List<RevenueByRouteDTO> result = analyticsService.getRevenueByRoute();

        assertThat(result).hasSize(1);
        RevenueByRouteDTO dto = result.get(0);
        assertThat(dto.routeId()).isEqualTo(routeId);
        assertThat(dto.routeName()).isEqualTo("Route 500K");
        assertThat(dto.totalRevenue()).isEqualByComparingTo("450.00");
    }

    @Test
    void getTicketsPerDay_returnsDescendingDates() {
        LocalDate today = LocalDate.of(2026, 9, 5);
        LocalDate yesterday = LocalDate.of(2026, 9, 4);
        LocalDate dayBefore = LocalDate.of(2026, 9, 3);

        when(ticketRepository.findTicketsPerDay())
                .thenReturn(List.of(
                        new Object[] {today, 5L},
                        new Object[] {yesterday, 3L},
                        new Object[] {dayBefore, 1L}));

        List<TicketsPerDayDTO> result = analyticsService.getTicketsPerDay();

        assertThat(result).hasSize(3);
        assertThat(result.get(0).date()).isEqualTo(today);
        assertThat(result.get(0).ticketCount()).isEqualTo(5L);
        assertThat(result.get(1).date()).isEqualTo(yesterday);
        assertThat(result.get(2).date()).isEqualTo(dayBefore);
    }

    @Test
    void getTopRoutes_orderedByVolume() {
        UUID busyRouteId = UUID.randomUUID();
        UUID quietRouteId = UUID.randomUUID();
        Route busyRoute = Route.builder().routeId(busyRouteId).routeName("Route 500K").build();
        Route quietRoute = Route.builder().routeId(quietRouteId).routeName("Route 201").build();

        when(ticketRepository.findTopRoutesByVolume())
                .thenReturn(List.of(new Object[] {busyRouteId, 40L}, new Object[] {quietRouteId, 5L}));
        when(routeRepository.findById(busyRouteId)).thenReturn(Optional.of(busyRoute));
        when(routeRepository.findById(quietRouteId)).thenReturn(Optional.of(quietRoute));

        List<TopRouteDTO> result = analyticsService.getTopRoutes();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).routeName()).isEqualTo("Route 500K");
        assertThat(result.get(0).ticketCount()).isEqualTo(40L);
        assertThat(result.get(1).routeName()).isEqualTo("Route 201");
        assertThat(result.get(1).ticketCount()).isEqualTo(5L);
    }

    @Test
    void getConductorActivity_returnsCorrectNames() {
        UUID conductorId = UUID.randomUUID();
        Conductor conductor = Conductor.builder().conductorId(conductorId).name("Ramesh Kumar").build();

        when(ticketRepository.findTicketsPerConductor())
                .thenReturn(List.<Object[]>of(new Object[] {conductorId, 12L}));
        when(conductorRepository.findById(conductorId)).thenReturn(Optional.of(conductor));

        List<ConductorActivityDTO> result = analyticsService.getConductorActivity();

        assertThat(result).hasSize(1);
        ConductorActivityDTO dto = result.get(0);
        assertThat(dto.conductorId()).isEqualTo(conductorId);
        assertThat(dto.conductorName()).isEqualTo("Ramesh Kumar");
        assertThat(dto.ticketsIssued()).isEqualTo(12L);
    }

    @Test
    void getRevenueByRoute_emptyResult() {
        when(ticketRepository.findRevenueByRoute()).thenReturn(List.<Object[]>of());

        List<RevenueByRouteDTO> result = analyticsService.getRevenueByRoute();

        assertThat(result).isEmpty();
    }
}
