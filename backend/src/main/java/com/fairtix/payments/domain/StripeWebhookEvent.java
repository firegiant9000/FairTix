package com.fairtix.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A Stripe webhook event FairTix has handled. Rows are written only through
 * {@code StripeWebhookEventRepository.insertIfAbsent}; the entity exists for reads.
 */
@Entity
@Table(name = "stripe_webhook_events")
public class StripeWebhookEvent {

  @Id
  @Column(name = "event_id", length = 255)
  private String eventId;

  @Column(nullable = false, name = "event_type", length = 100)
  private String eventType;

  @Column(nullable = false, name = "received_at", updatable = false)
  private Instant receivedAt;

  protected StripeWebhookEvent() {
  }

  public String getEventId() {
    return eventId;
  }

  public String getEventType() {
    return eventType;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }
}
