package com.fairtix.reports.infrastructure;

import com.fairtix.reports.domain.EventSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EventSettlementRepository extends JpaRepository<EventSettlement, UUID> {
  Optional<EventSettlement> findByEventId(UUID eventId);
}
