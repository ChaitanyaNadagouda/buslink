package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buslink.dto.request.CreateRouteRequestDTO;
import com.buslink.dto.request.CreateRouteStopDTO;
import com.buslink.dto.request.UpdateRouteRequestDTO;
import com.buslink.dto.response.RouteResponseDTO;
import com.buslink.dto.response.RouteStopResponseDTO;
import com.buslink.entity.Route;
import com.buslink.entity.RouteStop;
import com.buslink.enums.RouteStatus;
import com.buslink.exception.ConflictException;
import com.buslink.exception.ValidationException;
import com.buslink.repository.RouteRepository;
import com.buslink.repository.RouteStopRepository;
import java.math.BigDecimal;
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
class RouteServiceImplTest {

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private RouteStopRepository routeStopRepository;

    @InjectMocks
    private RouteServiceImpl routeService;

    private final UUID routeId = UUID.randomUUID();

    @Test
    @SuppressWarnings("unchecked")
    void createRoute_success() {
        CreateRouteRequestDTO request = new CreateRouteRequestDTO(
                "500K",
                "Test Route",
                new BigDecimal("6.00"),
                List.of(new CreateRouteStopDTO("Stop A", 1, 1), new CreateRouteStopDTO("Stop B", 2, 1)));

        when(routeRepository.findByRouteNumber("500K")).thenReturn(Optional.empty());
        when(routeRepository.save(any(Route.class))).thenAnswer(invocation -> {
            Route route = invocation.getArgument(0);
            route.setRouteId(routeId);
            return route;
        });

        RouteResponseDTO response = routeService.createRoute(request);

        assertThat(response.routeId()).isEqualTo(routeId);
        assertThat(response.routeNumber()).isEqualTo("500K");
        assertThat(response.totalStops()).isEqualTo(2);
        assertThat(response.originStop()).isEqualTo("Stop A");
        assertThat(response.destinationStop()).isEqualTo("Stop B");
        assertThat(response.status()).isEqualTo(RouteStatus.ACTIVE);

        ArgumentCaptor<List<RouteStop>> stopsCaptor = ArgumentCaptor.forClass(List.class);
        verify(routeStopRepository).saveAll(stopsCaptor.capture());
        assertThat(stopsCaptor.getValue()).hasSize(2);
    }

    @Test
    void createRoute_duplicateRouteNumber() {
        CreateRouteRequestDTO request = new CreateRouteRequestDTO(
                "500K", "Test Route", new BigDecimal("6.00"), List.of(new CreateRouteStopDTO("Stop A", 1, 1)));

        when(routeRepository.findByRouteNumber("500K"))
                .thenReturn(Optional.of(Route.builder().routeNumber("500K").build()));

        assertThatThrownBy(() -> routeService.createRoute(request)).isInstanceOf(ConflictException.class);
    }

    @Test
    void createRoute_duplicateStopNameInRequest() {
        CreateRouteRequestDTO request = new CreateRouteRequestDTO(
                "500K",
                "Test Route",
                new BigDecimal("6.00"),
                List.of(new CreateRouteStopDTO("Stop A", 1, 1), new CreateRouteStopDTO("Stop A", 2, 1)));

        when(routeRepository.findByRouteNumber("500K")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> routeService.createRoute(request)).isInstanceOf(ValidationException.class);
    }

    @Test
    void updateRoute_partialFareUpdate() {
        Route route = Route.builder()
                .routeId(routeId)
                .farePerStage(new BigDecimal("6.00"))
                .status(RouteStatus.ACTIVE)
                .build();
        UpdateRouteRequestDTO request = new UpdateRouteRequestDTO(new BigDecimal("8.00"), null);

        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(routeRepository.save(route)).thenReturn(route);

        RouteResponseDTO response = routeService.updateRoute(routeId, request);

        assertThat(response.farePerStage()).isEqualByComparingTo("8.00");
        assertThat(response.status()).isEqualTo(RouteStatus.ACTIVE);
    }

    @Test
    void deleteRoute_setsStatusInactive() {
        Route route =
                Route.builder().routeId(routeId).status(RouteStatus.ACTIVE).build();

        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(routeRepository.save(route)).thenReturn(route);

        RouteResponseDTO response = routeService.deleteRoute(routeId);

        assertThat(response.status()).isEqualTo(RouteStatus.INACTIVE);
    }

    @Test
    void addStop_success() {
        Route route = Route.builder()
                .routeId(routeId)
                .totalStops(2)
                .destinationStop("Stop B")
                .build();
        RouteStop stopA =
                RouteStop.builder().stopSequence(1).stageNumber(1).stopName("Stop A").build();
        RouteStop stopB =
                RouteStop.builder().stopSequence(2).stageNumber(1).stopName("Stop B").build();
        CreateRouteStopDTO request = new CreateRouteStopDTO("Stop C", 3, 2);

        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop C")).thenReturn(Optional.empty());
        when(routeStopRepository.findByRouteIdOrderByStopSequenceAsc(routeId)).thenReturn(List.of(stopA, stopB));
        when(routeStopRepository.save(any(RouteStop.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RouteStopResponseDTO response = routeService.addStop(routeId, request);

        assertThat(response.stopName()).isEqualTo("Stop C");
        assertThat(response.stopSequence()).isEqualTo(3);
        assertThat(response.stageNumber()).isEqualTo(2);
        assertThat(route.getTotalStops()).isEqualTo(3);
        assertThat(route.getDestinationStop()).isEqualTo("Stop C");
    }

    @Test
    void addStop_wrongSequence_throwsValidation() {
        Route route = Route.builder().routeId(routeId).totalStops(2).build();
        CreateRouteStopDTO request = new CreateRouteStopDTO("Stop C", 5, 2);

        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop C")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> routeService.addStop(routeId, request))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("stopSequence must be 3");
    }

    @Test
    void addStop_lowerStageNumber_throwsValidation() {
        Route route = Route.builder().routeId(routeId).totalStops(2).build();
        RouteStop lastStop =
                RouteStop.builder().stopSequence(2).stageNumber(5).stopName("Stop B").build();
        CreateRouteStopDTO request = new CreateRouteStopDTO("Stop C", 3, 3);

        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Stop C")).thenReturn(Optional.empty());
        when(routeStopRepository.findByRouteIdOrderByStopSequenceAsc(routeId)).thenReturn(List.of(lastStop));

        assertThatThrownBy(() -> routeService.addStop(routeId, request)).isInstanceOf(ValidationException.class);
    }
}
