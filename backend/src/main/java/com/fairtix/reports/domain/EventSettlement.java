package com.fairtix.reports.domain;

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

/**
 * Persisted split / tax configuration for one event's settlement. Numbers in
 * the actual settlement report compute live; this row only stores the
 * agreement: what fraction of the net goes to the artist, what the venue
 * takes off the top, and what tax rate to apply. Finalization stamps the
 * row so the artist's signed PDF references a frozen agreement.
 */
@Entity
@Table(name = "event_settlements")
public class EventSettlement {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Enumerated(EnumType.STRING)
  @Column(name = "split_type", length = 32)
  private SplitType splitType;

  /** 0..1, e.g. 0.85 for an 85/15 artist deal. */
  @Column(name = "artist_pct", precision = 5, scale = 4)
  private BigDecimal artistPct;

  /** Door deal: venue retains this off the top before the percentage split. */
  @Column(name = "venue_take_off_top", precision = 10, scale = 2)
  private BigDecimal venueTakeOffTop;

  /** Per-event tax rate override; falls back to organization default when null. */
  @Column(name = "tax_rate_pct", precision = 5, scale = 4)
  private BigDecimal taxRatePct;

  @Column(columnDefinition = "TEXT")
  private String notes;

  @Column(name = "finalized_at")
  private Instant finalizedAt;

  @Column(name = "finalized_by_user_id")
  private UUID finalizedByUserId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt = Instant.now();

  protected EventSettlement() {}

  public EventSettlement(UUID eventId) {
    this.eventId = eventId;
  }

  public void updateConfig(SplitType type, BigDecimal artistPct, BigDecimal venueTakeOffTop,
                           BigDecimal taxRatePct, String notes) {
    if (this.finalizedAt != null) {
      throw new IllegalStateException("Settlement already finalized; reopen before changing");
    }
    this.splitType = type;
    this.artistPct = artistPct;
    this.venueTakeOffTop = venueTakeOffTop;
    this.taxRatePct = taxRatePct;
    this.notes = notes;
    this.updatedAt = Instant.now();
  }

  public void finalize(UUID userId) {
    if (this.finalizedAt != null) return;
    this.finalizedAt = Instant.now();
    this.finalizedByUserId = userId;
    this.updatedAt = this.finalizedAt;
  }

  public void reopen() {
    this.finalizedAt = null;
    this.finalizedByUserId = null;
    this.updatedAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getEventId() { return eventId; }
  public SplitType getSplitType() { return splitType; }
  public BigDecimal getArtistPct() { return artistPct; }
  public BigDecimal getVenueTakeOffTop() { return venueTakeOffTop; }
  public BigDecimal getTaxRatePct() { return taxRatePct; }
  public String getNotes() { return notes; }
  public Instant getFinalizedAt() { return finalizedAt; }
  public UUID getFinalizedByUserId() { return finalizedByUserId; }
  public boolean isFinalized() { return finalizedAt != null; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
