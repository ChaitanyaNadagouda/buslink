package com.buslink.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.buslink.dto.response.FareResponseDTO;
import com.buslink.dto.response.RouteStopResponseDTO;
import com.buslink.entity.Route;
import com.buslink.entity.RouteStop;
import com.buslink.exception.ResourceNotFoundException;
import com.buslink.exception.ValidationException;
import com.buslink.repository.RouteRepository;
import com.buslink.repository.RouteStopRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FareServiceImplTest {

    @Mock
    private RouteRepository routeRepository;

    @Mock
    private RouteStopRepository routeStopRepository;

    @InjectMocks
    private FareServiceImpl fareService;

    private final UUID routeId = UUID.randomUUID();

    private RouteStop stop(int sequence, int stage, String name) {
        return RouteStop.builder()
                .routeId(routeId)
                .stopName(name)
                .stopSequence(sequence)
                .stageNumber(stage)
                .build();
    }

    @Test
    void calculateFare_sameStage() {
        RouteStop origin = stop(1, 1, "Banashankari Bus Station");
        RouteStop destination = stop(2, 1, "Sangam Circle");
        Route route = Route.builder().farePerStage(new BigDecimal("6.00")).build();

        when(routeStopRepository.findByRouteIdAndStopName(routeId, origin.getStopName()))
                .thenReturn(Optional.of(origin));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, destination.getStopName()))
                .thenReturn(Optional.of(destination));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));

        FareResponseDTO fare =
                fareService.calculateFare(routeId, origin.getStopName(), destination.getStopName(), 1, 0, 0);

        assertThat(fare.stagesCrossed()).isEqualTo(1);
        assertThat(fare.adultFare()).isEqualByComparingTo("6.00");
        assertThat(fare.childFare()).isEqualByComparingTo("3.00");
        assertThat(fare.totalFare()).isEqualByComparingTo("6.00");
    }

    @Test
    void calculateFare_multipleStages() {
        RouteStop origin = stop(9, 5, "HSR Layout");
        RouteStop destination = stop(19, 10, "KR Puram Railway Station");
        Route route = Route.builder().farePerStage(new BigDecimal("6.00")).build();

        when(routeStopRepository.findByRouteIdAndStopName(routeId, origin.getStopName()))
                .thenReturn(Optional.of(origin));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, destination.getStopName()))
                .thenReturn(Optional.of(destination));
        when(routeRepository.findById(routeId)).thenReturn(Optional.of(route));

        FareResponseDTO fare =
                fareService.calculateFare(routeId, origin.getStopName(), destination.getStopName(), 2, 1, 1);

        assertThat(fare.stagesCrossed()).isEqualTo(6);
        assertThat(fare.adultFare()).isEqualByComparingTo("36.00");
        assertThat(fare.childFare()).isEqualByComparingTo("18.00");
        assertThat(fare.infantFare()).isEqualByComparingTo("0");
        assertThat(fare.totalFare()).isEqualByComparingTo("90.00");
    }

    @Test
    void calculateFare_destinationBeforeOrigin() {
        RouteStop origin = stop(10, 5, "Agara Junction");
        RouteStop destination = stop(5, 3, "Ragigudda");

        when(routeStopRepository.findByRouteIdAndStopName(routeId, origin.getStopName()))
                .thenReturn(Optional.of(origin));
        when(routeStopRepository.findByRouteIdAndStopName(routeId, destination.getStopName()))
                .thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> fareService.calculateFare(
                        routeId, origin.getStopName(), destination.getStopName(), 1, 0, 0))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Destination must be after origin");
    }

    @Test
    void calculateFare_invalidStop() {
        when(routeStopRepository.findByRouteIdAndStopName(routeId, "Nonexistent Stop"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> fareService.calculateFare(routeId, "Nonexistent Stop", "HSR Layout", 1, 0, 0))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void searchStopsAfter_returnsOnlyForwardStops() {
        RouteStop origin = stop(9, 5, "HSR Layout");
        RouteStop forwardStop = stop(14, 7, "Kadabisanahalli");

        when(routeStopRepository.findByRouteIdAndStopName(routeId, origin.getStopName()))
                .thenReturn(Optional.of(origin));
        when(routeStopRepository
                        .findByRouteIdAndStopSequenceGreaterThanAndStopNameStartingWithIgnoreCaseOrderByStopSequenceAsc(
                                routeId, origin.getStopSequence(), "K"))
                .thenReturn(List.of(forwardStop));

        List<RouteStopResponseDTO> result = fareService.searchStopsAfter(routeId, origin.getStopName(), "K");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).stopName()).isEqualTo("Kadabisanahalli");
        assertThat(result.get(0).stopSequence()).isGreaterThan(origin.getStopSequence());
    }
}
