package com.buslink.repository;

import com.buslink.entity.Payment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    // for UPI later — Payment.referenceId is generic (a Payment isn't always
    // ticket-related, e.g. a wallet top-up), so this looks up by that field
    // rather than a ticket-specific one
    Optional<Payment> findByReferenceId(UUID referenceId);
}
