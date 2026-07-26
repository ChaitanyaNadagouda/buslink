package com.buslink.repository;

import com.buslink.entity.Conductor;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConductorRepository extends JpaRepository<Conductor, UUID> {

    Optional<Conductor> findByEmail(String email);

    Optional<Conductor> findByBusId(UUID busId);
}
