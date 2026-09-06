package com.buslink.repository;

import com.buslink.entity.Ticket;
import com.buslink.enums.TicketStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    List<Ticket> findByConductorIdAndStatusOrderByIssuedAtAsc(UUID conductorId, TicketStatus status);

    List<Ticket> findByUserIdOrderByIssuedAtDesc(UUID userId);

    Optional<Ticket> findByTicketIdAndConductorId(UUID ticketId, UUID conductorId);

    Optional<Ticket> findByTicketIdAndUserId(UUID ticketId, UUID userId);

    @Query("SELECT t.routeId, SUM(t.totalFare) FROM Ticket t "
            + "WHERE t.status = 'PAID' GROUP BY t.routeId "
            + "ORDER BY SUM(t.totalFare) DESC")
    List<Object[]> findRevenueByRoute();

    @Query("SELECT CAST(t.issuedAt AS date), COUNT(t) FROM Ticket t "
            + "GROUP BY CAST(t.issuedAt AS date) "
            + "ORDER BY CAST(t.issuedAt AS date) DESC")
    List<Object[]> findTicketsPerDay();

    @Query("SELECT t.routeId, COUNT(t) FROM Ticket t " + "GROUP BY t.routeId ORDER BY COUNT(t) DESC")
    List<Object[]> findTopRoutesByVolume();

    @Query("SELECT t.conductorId, COUNT(t) FROM Ticket t " + "GROUP BY t.conductorId ORDER BY COUNT(t) DESC")
    List<Object[]> findTicketsPerConductor();
}
