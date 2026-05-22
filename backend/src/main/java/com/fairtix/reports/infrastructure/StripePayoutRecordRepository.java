package com.fairtix.reports.infrastructure;

import com.fairtix.reports.domain.StripePayoutRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StripePayoutRecordRepository extends JpaRepository<StripePayoutRecord, UUID> {
  Optional<StripePayoutRecord> findByStripePayoutId(String stripePayoutId);
  List<StripePayoutRecord> findAllByOrganizationIdOrderByPaidAtDescCreatedAtDesc(UUID organizationId);
  List<StripePayoutRecord> findAllByOrganizationIdAndPaidAtBetween(UUID organizationId, Instant from, Instant to);
}
