package com.buslink.repository;

import com.buslink.entity.Route;
import com.buslink.enums.RouteStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RouteRepository extends JpaRepository<Route, UUID> {

    Optional<Route> findByRouteNumber(String routeNumber);

    List<Route> findByStatus(RouteStatus status);
}
