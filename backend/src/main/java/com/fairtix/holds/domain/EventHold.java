package com.fairtix.holds.domain;

import com.fairtix.events.domain.Event;
import com.fairtix.inventory.domain.Seat;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Promoter-side reservation of a seat for a non-paid purpose (artist/press/house).
 *
 * <p>Distinct from {@link com.fairtix.inventory.domain.SeatHold}, which is the
 * short-lived cart hold used during checkout. An event hold lives until the
 * promoter releases it, converts it into a comp ticket, or the optional
 * auto-release timer fires.
 */
@Entity
@Table(name = "event_holds", indexes = {
    @Index(name = "idx_event_holds_event_category", columnList = "event_id,category")
})
public class EventHold {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "event_id", nullable = false)
  private Event event;

  @ManyToOne(fetch = FetchType.EAGER, optional = false)
  @JoinColumn(name = "seat_id", nullable = false)
  private Seat seat;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private HoldCategory category;

  @Column(columnDefinition = "TEXT")
  private String note;

  @Column(name = "created_by", nullable = false, updatable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "auto_release_at")
  private Instant autoReleaseAt;

  @Column(name = "released_at")
  private Instant releasedAt;

  @Column(name = "released_by")
  private UUID releasedBy;

  @Column(name = "converted_ticket_id")
  private UUID convertedTicketId;

  protected EventHold() {}

  public EventHold(Event event, Seat seat, HoldCategory category, String note,
                   UUID createdBy, Instant autoReleaseAt) {
    this.event = event;
    this.seat = seat;
    this.category = category;
    this.note = note;
    this.createdBy = createdBy;
    this.createdAt = Instant.now();
    this.autoReleaseAt = autoReleaseAt;
  }

  public boolean isActive() {
    return releasedAt == null && convertedTicketId == null;
  }

  public void markReleased(UUID byUser) {
    this.releasedAt = Instant.now();
    this.releasedBy = byUser;
  }

  public void markConverted(UUID ticketId) {
    this.convertedTicketId = ticketId;
    this.releasedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public Event getEvent() { return event; }
  public Seat getSeat() { return seat; }
  public HoldCategory getCategory() { return category; }
  public String getNote() { return note; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getAutoReleaseAt() { return autoReleaseAt; }
  public Instant getReleasedAt() { return releasedAt; }
  public UUID getReleasedBy() { return releasedBy; }
  public UUID getConvertedTicketId() { return convertedTicketId; }
}
