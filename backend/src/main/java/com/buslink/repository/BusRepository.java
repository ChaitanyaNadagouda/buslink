package com.buslink.repository;

import com.buslink.entity.Bus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BusRepository extends JpaRepository<Bus, UUID> {

    Optional<Bus> findByBusNumber(String busNumber);

    List<Bus> findByRouteId(UUID routeId);
}
