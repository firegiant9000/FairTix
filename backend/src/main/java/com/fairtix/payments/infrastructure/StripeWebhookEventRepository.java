package com.fairtix.payments.infrastructure;

import com.fairtix.payments.domain.StripeWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface StripeWebhookEventRepository extends JpaRepository<StripeWebhookEvent, String> {

  /**
   * Records the event in the caller's transaction. Returns 1 for a new event, 0 when the event
   * is already recorded (a redelivery). A concurrent delivery of the same event waits on the
   * unique key until the first one's transaction ends: 0 if it committed, 1 if it rolled back.
   * Never a transaction of its own, so the row commits or rolls back with the handling.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @Modifying
  @Query(value = "INSERT INTO stripe_webhook_events (event_id, event_type)"
      + " VALUES (:eventId, :eventType) ON CONFLICT (event_id) DO NOTHING", nativeQuery = true)
  int insertIfAbsent(@Param("eventId") String eventId, @Param("eventType") String eventType);
}
