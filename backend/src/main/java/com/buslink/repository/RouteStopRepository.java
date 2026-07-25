package com.buslink.repository;

import com.buslink.entity.RouteStop;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RouteStopRepository extends JpaRepository<RouteStop, UUID> {

    List<RouteStop> findByRouteIdOrderByStopSequenceAsc(UUID routeId);

    Optional<RouteStop> findByRouteIdAndStopName(UUID routeId, String stopName);

    List<RouteStop> findByRouteIdAndStopNameContainingIgnoreCaseOrderByStopSequenceAsc(
            UUID routeId, String search);

    List<RouteStop> findByRouteIdAndStopSequenceGreaterThanOrderByStopSequenceAsc(
            UUID routeId, Integer sequence);
}
