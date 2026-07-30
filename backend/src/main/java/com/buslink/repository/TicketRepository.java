package com.buslink.repository;

import com.buslink.entity.Ticket;
import com.buslink.enums.TicketStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    List<Ticket> findByConductorIdAndStatusOrderByIssuedAtAsc(UUID conductorId, TicketStatus status);

    List<Ticket> findByUserIdOrderByIssuedAtDesc(UUID userId);

    Optional<Ticket> findByTicketIdAndConductorId(UUID ticketId, UUID conductorId);

    Optional<Ticket> findByTicketIdAndUserId(UUID ticketId, UUID userId);
}
