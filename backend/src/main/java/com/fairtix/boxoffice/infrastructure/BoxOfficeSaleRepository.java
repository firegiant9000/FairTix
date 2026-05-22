package com.fairtix.boxoffice.infrastructure;

import com.fairtix.boxoffice.domain.BoxOfficeSale;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BoxOfficeSaleRepository extends JpaRepository<BoxOfficeSale, UUID> {

  List<BoxOfficeSale> findAllBySessionIdOrderByCreatedAtAsc(UUID sessionId);

  Optional<BoxOfficeSale> findByStripePaymentIntentId(String paymentIntentId);
}
