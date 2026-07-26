package com.buslink.config;

import com.buslink.entity.Bus;
import com.buslink.entity.Conductor;
import com.buslink.entity.Route;
import com.buslink.entity.RouteStop;
import com.buslink.enums.ConductorStatus;
import com.buslink.enums.RouteStatus;
import com.buslink.repository.BusRepository;
import com.buslink.repository.ConductorRepository;
import com.buslink.repository.RouteRepository;
import com.buslink.repository.RouteStopRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private record SeedStop(int stopSequence, int stageNumber, String stopName) {}

    private static final List<SeedStop> ROUTE_500K_STOPS = List.of(
            new SeedStop(1, 1, "Banashankari Bus Station"),
            new SeedStop(2, 1, "Sangam Circle"),
            new SeedStop(3, 2, "Jayanagar 5th Block"),
            new SeedStop(4, 2, "Aurobindo Circle (JP Nagar)"),
            new SeedStop(5, 3, "Ragigudda"),
            new SeedStop(6, 3, "Jayadeva Hospital"),
            new SeedStop(7, 4, "BTM Layout"),
            new SeedStop(8, 4, "Central Silk Board"),
            new SeedStop(9, 5, "HSR Layout"),
            new SeedStop(10, 5, "Agara Junction"),
            new SeedStop(11, 6, "Iblur"),
            new SeedStop(12, 6, "Bellandur"),
            new SeedStop(13, 7, "Devarabisanahalli"),
            new SeedStop(14, 7, "Kadabisanahalli"),
            new SeedStop(15, 8, "Marathahalli Bridge"),
            new SeedStop(16, 8, "Karthiknagar"),
            new SeedStop(17, 9, "Doddanekkundi"),
            new SeedStop(18, 9, "Mahadevapura"),
            new SeedStop(19, 10, "KR Puram Railway Station"),
            new SeedStop(20, 10, "Tin Factory"),
            new SeedStop(21, 11, "Kasturi Nagar"),
            new SeedStop(22, 11, "Babusab Palya"),
            new SeedStop(23, 12, "Kalyan Nagar"),
            new SeedStop(24, 12, "Hennur Junction"),
            new SeedStop(25, 13, "HBR Layout"),
            new SeedStop(26, 13, "Nagavara Junction (Manyata Tech Park)"),
            new SeedStop(27, 14, "Veeranna Palya"),
            new SeedStop(28, 14, "Kempapura"),
            new SeedStop(29, 15, "Hebbal Bridge"));

    private final RouteRepository routeRepository;
    private final RouteStopRepository routeStopRepository;
    private final BusRepository busRepository;
    private final ConductorRepository conductorRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (routeRepository.count() > 0) {
            return;
        }

        Route route = Route.builder()
                .routeNumber("500K")
                .routeName("Banashankari to Hebbal")
                .farePerStage(new BigDecimal("6.00"))
                .totalStops(ROUTE_500K_STOPS.size())
                .originStop(ROUTE_500K_STOPS.get(0).stopName())
                .destinationStop(ROUTE_500K_STOPS.get(ROUTE_500K_STOPS.size() - 1).stopName())
                .status(RouteStatus.ACTIVE)
                .build();
        route = routeRepository.save(route);
        UUID routeId = route.getRouteId();

        List<RouteStop> routeStops = ROUTE_500K_STOPS.stream()
                .map(stop -> RouteStop.builder()
                        .routeId(routeId)
                        .stopName(stop.stopName())
                        .stopSequence(stop.stopSequence())
                        .stageNumber(stop.stageNumber())
                        .build())
                .toList();
        routeStopRepository.saveAll(routeStops);

        Bus bus = Bus.builder()
                .busNumber("KA-01-F-1234")
                .routeId(route.getRouteId())
                .build();
        bus = busRepository.save(bus);

        Conductor conductor = Conductor.builder()
                .name("Test Conductor")
                .email("conductor@buslink.com")
                .passwordHash(passwordEncoder.encode("Test@1234"))
                .busId(bus.getBusId())
                .status(ConductorStatus.ACTIVE)
                .build();
        conductorRepository.save(conductor);
    }
}
