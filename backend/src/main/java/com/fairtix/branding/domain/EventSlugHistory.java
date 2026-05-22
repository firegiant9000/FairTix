package com.fairtix.branding.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Records a previously-used event slug so deep links from social media keep
 * working after the organizer renames an event. The public lookup endpoint
 * 301-redirects from old slug to current.
 */
@Entity
@Table(name = "event_slug_history")
public class EventSlugHistory {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(name = "old_slug", nullable = false, length = 140)
  private String oldSlug;

  @Column(name = "retired_at", nullable = false)
  private Instant retiredAt = Instant.now();

  protected EventSlugHistory() {}

  public EventSlugHistory(UUID eventId, UUID organizationId, String oldSlug) {
    this.eventId = eventId;
    this.organizationId = organizationId;
    this.oldSlug = oldSlug;
  }

  public UUID getId() { return id; }
  public UUID getEventId() { return eventId; }
  public UUID getOrganizationId() { return organizationId; }
  public String getOldSlug() { return oldSlug; }
  public Instant getRetiredAt() { return retiredAt; }
}
