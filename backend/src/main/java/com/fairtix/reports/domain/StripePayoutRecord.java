package com.fairtix.reports.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Cached Stripe Connect payout row, hydrated from {@code payout.paid} /
 * {@code payout.failed} webhooks and on-demand sync. Lets the payout report
 * render without round-tripping to Stripe on every request and survives
 * deauthorization of the connected account.
 */
@Entity
@Table(name = "stripe_payouts")
public class StripePayoutRecord {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "stripe_payout_id", nullable = false, unique = true, length = 64)
  private String stripePayoutId;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false, length = 3)
  private String currency;

  @Column(nullable = false, length = 32)
  private String status;

  @Column(name = "arrival_date")
  private LocalDate arrivalDate;

  @Column(name = "paid_at")
  private Instant paidAt;

  @Column(name = "failure_code", length = 64)
  private String failureCode;

  @Column(name = "failure_message", length = 255)
  private String failureMessage;

  @Column(name = "raw_json", columnDefinition = "TEXT")
  private String rawJson;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  protected StripePayoutRecord() {}

  public StripePayoutRecord(UUID organizationId, String stripePayoutId, BigDecimal amount,
                            String currency, String status, LocalDate arrivalDate,
                            Instant paidAt, String failureCode, String failureMessage,
                            String rawJson) {
    this.organizationId = organizationId;
    this.stripePayoutId = stripePayoutId;
    this.amount = amount;
    this.currency = currency;
    this.status = status;
    this.arrivalDate = arrivalDate;
    this.paidAt = paidAt;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    this.rawJson = rawJson;
  }

  public void updateFrom(String status, LocalDate arrivalDate, Instant paidAt,
                         String failureCode, String failureMessage, String rawJson) {
    this.status = status;
    this.arrivalDate = arrivalDate;
    this.paidAt = paidAt;
    this.failureCode = failureCode;
    this.failureMessage = failureMessage;
    if (rawJson != null) this.rawJson = rawJson;
  }

  public UUID getId() { return id; }
  public UUID getOrganizationId() { return organizationId; }
  public String getStripePayoutId() { return stripePayoutId; }
  public BigDecimal getAmount() { return amount; }
  public String getCurrency() { return currency; }
  public String getStatus() { return status; }
  public LocalDate getArrivalDate() { return arrivalDate; }
  public Instant getPaidAt() { return paidAt; }
  public String getFailureCode() { return failureCode; }
  public String getFailureMessage() { return failureMessage; }
  public String getRawJson() { return rawJson; }
  public Instant getCreatedAt() { return createdAt; }
}
