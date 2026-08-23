package com.buslink.repository;

import com.buslink.entity.Payment;
import com.buslink.enums.PaymentStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // referenceId (e.g. a ticketId) isn't unique on its own — a ticket can
    // accumulate FAILED payment rows across retries — but at most one PENDING
    // row can ever exist per referenceId by construction, so scoping to
    // status keeps this a safe single-result lookup.
    Optional<Payment> findByReferenceIdAndStatus(UUID referenceId, PaymentStatus status);

    // gatewayReferenceId is unique (@Column(unique = true)), so a single-result
    // lookup is always safe here.
    Optional<Payment> findByGatewayReferenceId(String gatewayReferenceId);
}
