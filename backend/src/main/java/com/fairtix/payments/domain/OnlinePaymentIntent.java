package com.fairtix.payments.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "online_payment_intents")
public class OnlinePaymentIntent {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(nullable = false, name = "user_id")
  private UUID userId;

  @Column(nullable = false, name = "hold_set_hash", length = 64)
  private String holdSetHash;

  @Column(nullable = false, name = "amount_cents")
  private long amountCents;

  @Column(nullable = false, name = "routing_hash", length = 64)
  private String routingHash;

  @Column(nullable = false, name = "stripe_payment_intent_id", unique = true)
  private String stripePaymentIntentId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private OnlinePaymentIntentStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "invalidated_reason", length = 40)
  private IntentInvalidationReason invalidatedReason;

  @Column(nullable = false, name = "created_at", updatable = false)
  private Instant createdAt;

  @Column(name = "invalidated_at")
  private Instant invalidatedAt;

  protected OnlinePaymentIntent() {
  }

  public OnlinePaymentIntent(UUID userId, String holdSetHash, long amountCents,
      String routingHash, String stripePaymentIntentId) {
    this.userId = userId;
    this.holdSetHash = holdSetHash;
    this.amountCents = amountCents;
    this.routingHash = routingHash;
    this.stripePaymentIntentId = stripePaymentIntentId;
    this.status = OnlinePaymentIntentStatus.ACTIVE;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getHoldSetHash() {
    return holdSetHash;
  }

  public long getAmountCents() {
    return amountCents;
  }

  public String getRoutingHash() {
    return routingHash;
  }

  public String getStripePaymentIntentId() {
    return stripePaymentIntentId;
  }

  public OnlinePaymentIntentStatus getStatus() {
    return status;
  }

  public IntentInvalidationReason getInvalidatedReason() {
    return invalidatedReason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getInvalidatedAt() {
    return invalidatedAt;
  }
}
