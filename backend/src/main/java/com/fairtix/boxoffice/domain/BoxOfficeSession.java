package com.fairtix.boxoffice.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "box_office_sessions")
public class BoxOfficeSession {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "staff_user_id", nullable = false)
  private UUID staffUserId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private SessionStatus status = SessionStatus.OPEN;

  @Column(name = "opening_cash", nullable = false, precision = 10, scale = 2)
  private BigDecimal openingCash;

  @Column(name = "closing_cash", precision = 10, scale = 2)
  private BigDecimal closingCash;

  @Column(name = "expected_cash", precision = 10, scale = 2)
  private BigDecimal expectedCash;

  @Column(precision = 10, scale = 2)
  private BigDecimal variance;

  @Column(name = "variance_reason", columnDefinition = "TEXT")
  private String varianceReason;

  @Column(name = "signed_off_by_user_id")
  private UUID signedOffByUserId;

  @Column(name = "signed_off_at")
  private Instant signedOffAt;

  @Column(name = "opened_at", nullable = false, updatable = false)
  private Instant openedAt = Instant.now();

  @Column(name = "closed_at")
  private Instant closedAt;

  protected BoxOfficeSession() {}

  public BoxOfficeSession(UUID organizationId, UUID staffUserId, BigDecimal openingCash) {
    this.organizationId = organizationId;
    this.staffUserId = staffUserId;
    this.openingCash = openingCash;
  }

  public UUID getId() { return id; }
  public UUID getOrganizationId() { return organizationId; }
  public UUID getStaffUserId() { return staffUserId; }
  public SessionStatus getStatus() { return status; }
  public BigDecimal getOpeningCash() { return openingCash; }
  public BigDecimal getClosingCash() { return closingCash; }
  public BigDecimal getExpectedCash() { return expectedCash; }
  public BigDecimal getVariance() { return variance; }
  public String getVarianceReason() { return varianceReason; }
  public UUID getSignedOffByUserId() { return signedOffByUserId; }
  public Instant getSignedOffAt() { return signedOffAt; }
  public Instant getOpenedAt() { return openedAt; }
  public Instant getClosedAt() { return closedAt; }

  public void close(BigDecimal closingCash, BigDecimal expectedCash, BigDecimal variance,
                    String varianceReason, UUID signedOffBy) {
    this.closingCash = closingCash;
    this.expectedCash = expectedCash;
    this.variance = variance;
    this.varianceReason = varianceReason;
    this.signedOffByUserId = signedOffBy;
    this.signedOffAt = Instant.now();
    this.closedAt = Instant.now();
    this.status = SessionStatus.CLOSED;
  }
}
