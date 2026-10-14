package com.buslink.scheduler;

import com.buslink.config.TicketExpiryProperties;
import com.buslink.entity.Ticket;
import com.buslink.enums.TicketStatus;
import com.buslink.repository.TicketRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class TicketExpiryScheduler {

    private final TicketRepository ticketRepository;
    private final TicketExpiryProperties ticketExpiryProperties;

    @Scheduled(fixedDelayString = "${ticket.expiry.check-interval-ms}")
    @Transactional
    public void expireStaleTickets() {
        Instant threshold = Instant.now().minus(ticketExpiryProperties.thresholdHours(), ChronoUnit.HOURS);
        // DISPUTED is deliberately excluded (findByStatusAndIssuedAtBefore is scoped to ISSUED only) —
        // it's a resolvable debt the passenger can still pay, not an abandoned ticket to write off.
        List<Ticket> staleTickets = ticketRepository.findByStatusAndIssuedAtBefore(TicketStatus.ISSUED, threshold);
        if (staleTickets.isEmpty()) {
            return;
        }

        staleTickets.forEach(ticket -> ticket.setStatus(TicketStatus.EXPIRED));
        ticketRepository.saveAll(staleTickets);
        log.info("TicketExpiryScheduler: expired {} stale ISSUED ticket(s)", staleTickets.size());
    }
}
