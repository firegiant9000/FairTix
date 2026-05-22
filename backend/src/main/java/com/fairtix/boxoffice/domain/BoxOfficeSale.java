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
@Table(name = "box_office_sales")
public class BoxOfficeSale {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "session_id", nullable = false)
  private UUID sessionId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "order_id")
  private UUID orderId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private SaleMethod method;

  @Column(nullable = false, precision = 10, scale = 2)
  private BigDecimal amount;

  @Column(name = "seat_count", nullable = false)
  private int seatCount;

  @Column(name = "customer_email", length = 255)
  private String customerEmail;

  @Column(name = "customer_name", length = 255)
  private String customerName;

  @Column(name = "comp_reason", columnDefinition = "TEXT")
  private String compReason;

  @Column(name = "stripe_payment_intent_id", length = 64)
  private String stripePaymentIntentId;

  @Column(name = "terminal_reader_id", length = 64)
  private String terminalReaderId;

  @Column(name = "staff_user_id", nullable = false)
  private UUID staffUserId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  protected BoxOfficeSale() {}

  public BoxOfficeSale(UUID sessionId, UUID organizationId, UUID eventId, UUID orderId,
                       SaleMethod method, BigDecimal amount, int seatCount,
                       String customerEmail, String customerName, String compReason,
                       String stripePaymentIntentId, String terminalReaderId, UUID staffUserId) {
    this.sessionId = sessionId;
    this.organizationId = organizationId;
    this.eventId = eventId;
    this.orderId = orderId;
    this.method = method;
    this.amount = amount;
    this.seatCount = seatCount;
    this.customerEmail = customerEmail;
    this.customerName = customerName;
    this.compReason = compReason;
    this.stripePaymentIntentId = stripePaymentIntentId;
    this.terminalReaderId = terminalReaderId;
    this.staffUserId = staffUserId;
  }

  /** Fills the customer + order linkage when a card-present hold is confirmed. */
  public void completeCard(UUID orderId, String customerEmail, String customerName) {
    this.orderId = orderId;
    this.customerEmail = customerEmail;
    this.customerName = customerName;
  }

  public UUID getId() { return id; }
  public UUID getSessionId() { return sessionId; }
  public UUID getOrganizationId() { return organizationId; }
  public UUID getEventId() { return eventId; }
  public UUID getOrderId() { return orderId; }
  public SaleMethod getMethod() { return method; }
  public BigDecimal getAmount() { return amount; }
  public int getSeatCount() { return seatCount; }
  public String getCustomerEmail() { return customerEmail; }
  public String getCustomerName() { return customerName; }
  public String getCompReason() { return compReason; }
  public String getStripePaymentIntentId() { return stripePaymentIntentId; }
  public String getTerminalReaderId() { return terminalReaderId; }
  public UUID getStaffUserId() { return staffUserId; }
  public Instant getCreatedAt() { return createdAt; }
}
