package com.fairtix.refunds.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refund_requests", indexes = {
    @Index(name = "idx_refund_order", columnList = "order_id"),
    @Index(name = "idx_refund_user", columnList = "user_id"),
    @Index(name = "idx_refund_status", columnList = "status")
})
public class RefundRequest {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(nullable = false, name = "order_id")
  private UUID orderId;

  @Column(nullable = false, name = "user_id")
  private UUID userId;

  @Column(nullable = false, precision = 10, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false)
  private String reason;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private RefundStatus status;

  @Column(name = "admin_notes")
  private String adminNotes;

  @Column(name = "reviewed_by")
  private UUID reviewedBy;

  @Column(name = "reviewed_at")
  private Instant reviewedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(nullable = false, name = "created_at", updatable = false)
  private Instant createdAt;

  @Column(nullable = false, name = "updated_at")
  private Instant updatedAt;

  @Column(name = "stripe_refund_id", unique = true)
  private String stripeRefundId;

  public RefundRequest(UUID orderId, UUID userId, BigDecimal amount, String reason) {
    this.orderId = orderId;
    this.userId = userId;
    this.amount = amount;
    this.reason = reason;
    this.status = RefundStatus.REQUESTED;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  protected RefundRequest() {
  }

  public UUID getId() { return id; }
  public UUID getOrderId() { return orderId; }
  public UUID getUserId() { return userId; }
  public BigDecimal getAmount() { return amount; }
  public String getReason() { return reason; }
  public RefundStatus getStatus() { return status; }
  public String getAdminNotes() { return adminNotes; }
  public UUID getReviewedBy() { return reviewedBy; }
  public Instant getReviewedAt() { return reviewedAt; }
  public Instant getCompletedAt() { return completedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public String getStripeRefundId() { return stripeRefundId; }

  public void setStripeRefundId(String stripeRefundId) {
    this.stripeRefundId = stripeRefundId;
    this.updatedAt = Instant.now();
  }

  public void approve(UUID adminId, String notes) {
    this.status = RefundStatus.APPROVED;
    this.reviewedBy = adminId;
    this.reviewedAt = Instant.now();
    this.adminNotes = notes;
    this.updatedAt = Instant.now();
  }

  public void holdForManualReview() {
    this.status = RefundStatus.PENDING_MANUAL;
    this.updatedAt = Instant.now();
  }

  public void reject(UUID adminId, String notes) {
    this.status = RefundStatus.REJECTED;
    this.reviewedBy = adminId;
    this.reviewedAt = Instant.now();
    this.adminNotes = notes;
    this.updatedAt = Instant.now();
  }

  public void complete() {
    this.status = RefundStatus.COMPLETED;
    this.completedAt = Instant.now();
    this.updatedAt = Instant.now();
  }

  public void cancel() {
    this.status = RefundStatus.CANCELLED;
    this.updatedAt = Instant.now();
  }
}
