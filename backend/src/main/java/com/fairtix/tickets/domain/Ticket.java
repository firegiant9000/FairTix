package com.fairtix.tickets.domain;

import com.fairtix.events.domain.Event;
import com.fairtix.inventory.domain.Seat;
import com.fairtix.orders.domain.Order;
import com.fairtix.users.domain.User;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tickets", indexes = {
    @Index(name = "idx_ticket_user_id", columnList = "user_id"),
    @Index(name = "idx_ticket_order_id", columnList = "order_id"),
    @Index(name = "idx_ticket_event_id", columnList = "event_id")
})
public class Ticket {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "order_id", nullable = false)
  private Order order;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "seat_id", nullable = false)
  private Seat seat;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "event_id", nullable = false)
  private Event event;

  @Column(nullable = false, precision = 10, scale = 2)
  private BigDecimal price;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TicketStatus status;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TicketKind kind = TicketKind.PAID;

  @Column(name = "kind_reason")
  private String kindReason;

  @Column(name = "kind_issued_by")
  private UUID kindIssuedBy;

  @Column(name = "sold_by_user_id")
  private UUID soldByUserId;

  @Column(name = "recipient_name")
  private String recipientName;

  @Column(name = "recipient_email")
  private String recipientEmail;

  @Column(name = "will_call", nullable = false)
  private boolean willCall = false;

  @Column(name = "will_call_claimed_at")
  private Instant willCallClaimedAt;

  @Column(name = "will_call_claimed_by")
  private UUID willCallClaimedBy;

  @Column(name = "issued_at", nullable = false, updatable = false)
  private Instant issuedAt;

  public Ticket(Order order, User user, Seat seat, Event event, BigDecimal price) {
    this.order = order;
    this.user = user;
    this.seat = seat;
    this.event = event;
    this.price = price;
    this.status = TicketStatus.VALID;
    this.kind = TicketKind.PAID;
    this.issuedAt = Instant.now();
  }

  public Ticket(Order order, User user, Seat seat, Event event, BigDecimal price,
                TicketKind kind, String kindReason, UUID issuedBy) {
    this(order, user, seat, event, price);
    this.kind = kind;
    this.kindReason = kindReason;
    this.kindIssuedBy = issuedBy;
  }

  protected Ticket() {
  }

  public UUID getId() { return id; }
  public Order getOrder() { return order; }
  public User getUser() { return user; }
  public Seat getSeat() { return seat; }
  public Event getEvent() { return event; }
  public BigDecimal getPrice() { return price; }
  public TicketStatus getStatus() { return status; }
  public Instant getIssuedAt() { return issuedAt; }

  public void setStatus(TicketStatus status) { this.status = status; }
  public void setUser(User user) { this.user = user; }

  public TicketKind getKind() { return kind; }
  public void setKind(TicketKind kind) { this.kind = kind; }
  public String getKindReason() { return kindReason; }
  public void setKindReason(String kindReason) { this.kindReason = kindReason; }
  public UUID getKindIssuedBy() { return kindIssuedBy; }
  public void setKindIssuedBy(UUID kindIssuedBy) { this.kindIssuedBy = kindIssuedBy; }
  public UUID getSoldByUserId() { return soldByUserId; }
  public void setSoldByUserId(UUID soldByUserId) { this.soldByUserId = soldByUserId; }
  public String getRecipientName() { return recipientName; }
  public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
  public String getRecipientEmail() { return recipientEmail; }
  public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }
  public boolean isWillCall() { return willCall; }
  public void setWillCall(boolean willCall) { this.willCall = willCall; }
  public Instant getWillCallClaimedAt() { return willCallClaimedAt; }
  public UUID getWillCallClaimedBy() { return willCallClaimedBy; }

  public void markWillCallClaimed(UUID claimedBy) {
    this.willCallClaimedAt = Instant.now();
    this.willCallClaimedBy = claimedBy;
  }
}
